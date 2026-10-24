package io.aria.conductor.common.event;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S8: RunProgressEvent contract — in-memory streaming event for the progress
 * pump; immutable, defensive copies, kind taxonomy, part identity.
 */
class RunProgressEventTest {

    @Test
    void carriesCoreFields() {
        UUID runId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();

        RunProgressEvent ev = new RunProgressEvent(this, runId, agentId, 2,
                RunProgressEvent.Kind.THINKING, "locating scheduler", null, 7, "prt_7");

        assertThat(ev.getRunId()).isEqualTo(runId);
        assertThat(ev.getAgentId()).isEqualTo(agentId);
        assertThat(ev.getIteration()).isEqualTo(2);
        assertThat(ev.getKind()).isEqualTo(RunProgressEvent.Kind.THINKING);
        assertThat(ev.getContent()).isEqualTo("locating scheduler");
        assertThat(ev.getToolName()).isNull();
        assertThat(ev.getSeq()).isEqualTo(7);
        assertThat(ev.getPartId()).isEqualTo("prt_7");
    }

    /**
     * Issue #103: a row is an OBSERVATION of a part, and the part is its identity.
     * Two events of one growing part therefore carry the SAME partId and different
     * seq — the pair the drawer merges into one line and the REST replay keeps both.
     * A null partId (a core that reported none, or a pre-V74 row) is a legitimate
     * identity-free row: seq is then the only key a consumer has.
     */
    @Test
    void onePartObservedTwiceSharesItsIdentityAndIncreasesOnlyItsSeq() {
        UUID runId = UUID.randomUUID();

        RunProgressEvent first = new RunProgressEvent(this, runId, UUID.randomUUID(), 1,
                RunProgressEvent.Kind.ANSWER, "Consensus needs", null, 1, "prt_1");
        RunProgressEvent grown = new RunProgressEvent(this, runId, UUID.randomUUID(), 1,
                RunProgressEvent.Kind.ANSWER, "Consensus needs a majority.", null, 2, "prt_1");
        RunProgressEvent unidentified = new RunProgressEvent(this, runId, UUID.randomUUID(), 1,
                RunProgressEvent.Kind.ANSWER, "no id from the core", null, 3, null);

        assertThat(grown.getPartId()).isEqualTo(first.getPartId());
        assertThat(grown.getSeq()).isGreaterThan(first.getSeq());
        assertThat(grown.getContent()).isNotEqualTo(first.getContent());
        assertThat(unidentified.getPartId()).isNull();
    }

    /**
     * The kind set is a table, not a hint: every consumer that maps a kind (the
     * drawer's affordance, the WS/REST payload, the pump's part-type mapping) is
     * reviewed when this set changes. {@code containsExactlyInAnyOrder} fails on
     * an added kind until every table is moved with it — this repo has already
     * been bitten by an enum value added without its mapping table.
     */
    @Test
    void kindTaxonomyCoversPumpSources() {
        assertThat(RunProgressEvent.Kind.values())
                .containsExactlyInAnyOrder(RunProgressEvent.Kind.THINKING,
                        RunProgressEvent.Kind.ANSWER,
                        RunProgressEvent.Kind.TOOL_CALL,
                        RunProgressEvent.Kind.TOOL_RESULT,
                        RunProgressEvent.Kind.STATUS,
                        RunProgressEvent.Kind.ERROR);
    }

    /**
     * Issue #103: the assistant's reply is its own kind. A THINKING fragment is
     * what the model works out, an ANSWER fragment is what it says back, and one
     * event may not stand in for the other.
     */
    @Test
    void answerIsAFirstClassKindDistinctFromThinking() {
        UUID runId = UUID.randomUUID();

        RunProgressEvent thinking = new RunProgressEvent(this, runId, UUID.randomUUID(), 1,
                RunProgressEvent.Kind.THINKING, "outlining the essay", null, 1, "prt_think");
        RunProgressEvent answer = new RunProgressEvent(this, runId, UUID.randomUUID(), 1,
                RunProgressEvent.Kind.ANSWER, "Consensus needs a majority.", null, 2, "prt_answer");

        assertThat(answer.getKind()).isEqualTo(RunProgressEvent.Kind.ANSWER)
                .isNotEqualTo(thinking.getKind());
        assertThat(answer.getContent()).isEqualTo("Consensus needs a majority.");
        assertThat(answer.getSeq()).isEqualTo(2);
        assertThat(answer.getPartId()).isEqualTo("prt_answer");
    }

    @Test
    void nullContentDefaultsToEmpty() {
        RunProgressEvent ev = new RunProgressEvent(this, UUID.randomUUID(), UUID.randomUUID(), 1,
                RunProgressEvent.Kind.TOOL_CALL, null, "bash", 1, "prt_tool");

        assertThat(ev.getContent()).isEmpty();
        assertThat(ev.getToolName()).isEqualTo("bash");
    }
}
