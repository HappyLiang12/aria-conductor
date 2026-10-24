package io.aria.conductor.common.event;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S8: RunProgressEvent contract — in-memory streaming event for the progress
 * pump; immutable, defensive copies, kind taxonomy.
 */
class RunProgressEventTest {

    @Test
    void carriesCoreFields() {
        UUID runId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();

        RunProgressEvent ev = new RunProgressEvent(this, runId, agentId, 2,
                RunProgressEvent.Kind.THINKING, "locating scheduler", null, 7);

        assertThat(ev.getRunId()).isEqualTo(runId);
        assertThat(ev.getAgentId()).isEqualTo(agentId);
        assertThat(ev.getIteration()).isEqualTo(2);
        assertThat(ev.getKind()).isEqualTo(RunProgressEvent.Kind.THINKING);
        assertThat(ev.getContent()).isEqualTo("locating scheduler");
        assertThat(ev.getToolName()).isNull();
        assertThat(ev.getSeq()).isEqualTo(7);
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
                RunProgressEvent.Kind.THINKING, "outlining the essay", null, 1);
        RunProgressEvent answer = new RunProgressEvent(this, runId, UUID.randomUUID(), 1,
                RunProgressEvent.Kind.ANSWER, "Consensus needs a majority.", null, 2);

        assertThat(answer.getKind()).isEqualTo(RunProgressEvent.Kind.ANSWER)
                .isNotEqualTo(thinking.getKind());
        assertThat(answer.getContent()).isEqualTo("Consensus needs a majority.");
        assertThat(answer.getSeq()).isEqualTo(2);
    }

    @Test
    void nullContentDefaultsToEmpty() {
        RunProgressEvent ev = new RunProgressEvent(this, UUID.randomUUID(), UUID.randomUUID(), 1,
                RunProgressEvent.Kind.TOOL_CALL, null, "bash", 1);

        assertThat(ev.getContent()).isEmpty();
        assertThat(ev.getToolName()).isEqualTo("bash");
    }
}
