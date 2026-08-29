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

    @Test
    void kindTaxonomyCoversPumpSources() {
        assertThat(RunProgressEvent.Kind.values())
                .contains(RunProgressEvent.Kind.THINKING,
                        RunProgressEvent.Kind.TOOL_CALL,
                        RunProgressEvent.Kind.TOOL_RESULT,
                        RunProgressEvent.Kind.STATUS,
                        RunProgressEvent.Kind.ERROR);
    }

    @Test
    void nullContentDefaultsToEmpty() {
        RunProgressEvent ev = new RunProgressEvent(this, UUID.randomUUID(), UUID.randomUUID(), 1,
                RunProgressEvent.Kind.TOOL_CALL, null, "bash", 1);

        assertThat(ev.getContent()).isEmpty();
        assertThat(ev.getToolName()).isEqualTo("bash");
    }
}
