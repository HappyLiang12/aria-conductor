package io.aria.conductor.common.event;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the real logic in {@link RunIterationEvent}'s constructors: the convenience constructor's
 * empty-list defaults and the full constructor's null-safe defensive copying of the tool-call and
 * skill lists.
 */
class RunIterationEventTest {

    private final UUID runId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();

    @Test
    void convenienceConstructor_defaultsToEmptyCollectionsAndNullThinking() {
        RunIterationEvent event = new RunIterationEvent(this, runId, agentId, 1, 10);

        assertThat(event.getRunId()).isEqualTo(runId);
        assertThat(event.getAgentId()).isEqualTo(agentId);
        assertThat(event.getIteration()).isEqualTo(1);
        assertThat(event.getMaxIterations()).isEqualTo(10);
        assertThat(event.getThinking()).isNull();
        assertThat(event.getToolCalls()).isEmpty();
        assertThat(event.getSkills()).isEmpty();
        assertThat(event.getSource()).isSameAs(this);
    }

    @Test
    void fullConstructor_nullCollections_becomeEmpty() {
        RunIterationEvent event = new RunIterationEvent(this, runId, agentId, 2, 5, "thinking", null, null);

        assertThat(event.getThinking()).isEqualTo("thinking");
        assertThat(event.getToolCalls()).isEmpty();
        assertThat(event.getSkills()).isEmpty();
    }

    @Test
    void fullConstructor_copiesCollectionsDefensively() {
        List<RunIterationEvent.ToolCallDetail> toolCalls = new ArrayList<>();
        toolCalls.add(new RunIterationEvent.ToolCallDetail("web_search", "{}", "ok"));
        List<String> skills = new ArrayList<>(List.of("research"));

        RunIterationEvent event = new RunIterationEvent(this, runId, agentId, 3, 8, "t", toolCalls, skills);

        // Mutating the source lists must not affect the event's snapshot.
        toolCalls.clear();
        skills.clear();

        assertThat(event.getToolCalls()).hasSize(1);
        assertThat(event.getToolCalls().get(0).name()).isEqualTo("web_search");
        assertThat(event.getSkills()).containsExactly("research");
    }

    @Test
    void toolCallsList_isImmutable() {
        RunIterationEvent event = new RunIterationEvent(this, runId, agentId, 1, 10, null,
                List.of(new RunIterationEvent.ToolCallDetail("a", "{}", "r")), List.of("s"));

        assertThatThrownBy(() -> event.getToolCalls().add(
                new RunIterationEvent.ToolCallDetail("b", "{}", "r")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void toolCallDetail_carriesFields() {
        RunIterationEvent.ToolCallDetail detail =
                new RunIterationEvent.ToolCallDetail("git_pack", "{\"cmd\":\"status\"}", "clean");
        assertThat(detail.name()).isEqualTo("git_pack");
        assertThat(detail.arguments()).isEqualTo("{\"cmd\":\"status\"}");
        assertThat(detail.result()).isEqualTo("clean");
    }
}
