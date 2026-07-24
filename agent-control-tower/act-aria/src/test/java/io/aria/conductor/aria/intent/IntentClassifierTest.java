package io.aria.conductor.aria.intent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keyword decision table for {@link IntentClassifier}. The classifier applies
 * keyword groups in a fixed priority order (agent.status > run.start >
 * approval.status > knowledge.query > dashboard.summary > general), so
 * several of these cases pin the tie-breaking behaviour.
 */
class IntentClassifierTest {

    private final IntentClassifier classifier = new IntentClassifier();

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
            "how many agents do we have         | agent.status",
            "show agents                        | agent.status",
            "what is the status of the system   | agent.status",
            "please execute the deployment      | run.start",
            "begin the nightly batch            | run.start",
            "deny that request                  | approval.status",
            "anything waiting for approval?     | approval.status",
            "what do we know about retries      | knowledge.query",
            "find the backup script             | knowledge.query",
            "do we have a skill for parsing     | knowledge.query",
            "give me an overview                | dashboard.summary",
            "weekly stats please                | dashboard.summary",
            "open the dashboard                 | dashboard.summary",
            "hello there                        | general",
            "thanks!                            | general",
    })
    void classify_mapsKeywordsToIntents(String message, String expectedIntent) {
        assertThat(classifier.classify(message)).isEqualTo(expectedIntent);
    }

    @Test
    void classify_isCaseInsensitive() {
        assertThat(classifier.classify("SHOW AGENTS")).isEqualTo("agent.status");
        assertThat(classifier.classify("EXECUTE now")).isEqualTo("run.start");
    }

    @Test
    void classify_agentKeywordsWinOverRunKeywords() {
        // "start" alone is run.start, but "agent" is checked first
        assertThat(classifier.classify("start the agent")).isEqualTo("agent.status");
    }

    @Test
    void classify_runKeywordsWinOverApprovalKeywords() {
        // "approve" alone is approval.status, but "run" is checked first
        assertThat(classifier.classify("approve the run")).isEqualTo("run.start");
    }

    @Test
    void classify_listKeywordShadowsApprovalPhrases() {
        // pins a known quirk: "list" matches agent.status before "pending"
        // ever gets a chance to classify as approval.status
        assertThat(classifier.classify("please show pending approvals")).isEqualTo("approval.status");
        assertThat(classifier.classify("list pending approvals")).isEqualTo("agent.status");
    }

    @Test
    void classify_substringMatchTriggersIntent() {
        // contains() semantics: "restart" contains "start"
        assertThat(classifier.classify("restart everything")).isEqualTo("run.start");
    }
}
