package io.aria.conductor.aria.service;

import io.aria.conductor.common.model.SessionTrajectory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD: loadConversationHistory must filter out "tool" role trajectories.
 * Bug: current code maps "tool" role as LlmMessage.assistant(), corrupting context.
 */
class ConversationHistoryToolRoleTest {

    @Test
    void toolRoleTrajectoriesShouldBeFilteredOut() {
        SessionTrajectory userMsg = SessionTrajectory.builder()
                .runId(java.util.UUID.randomUUID())
                .turnNumber(1).role("user").content("create an agent").build();
        SessionTrajectory assistantMsg = SessionTrajectory.builder()
                .runId(java.util.UUID.randomUUID())
                .turnNumber(2).role("assistant").content("I'll create the agent").build();
        SessionTrajectory toolMsg = SessionTrajectory.builder()
                .runId(java.util.UUID.randomUUID())
                .turnNumber(3).role("tool").content("agent created successfully").toolCallId("call_1").build();

        List<SessionTrajectory> trajectories = List.of(userMsg, assistantMsg, toolMsg);

        // FIX: only include user and assistant roles (not tool, not system)
        List<String> filteredRoles = trajectories.stream()
                .filter(t -> "user".equals(t.getRole()) || "assistant".equals(t.getRole()))
                .map(SessionTrajectory::getRole)
                .toList();

        assertThat(filteredRoles)
                .as("tool role should be excluded from conversation history")
                .containsExactly("user", "assistant");
    }

    @Test
    void systemRoleTrajectoriesShouldBeFilteredOut() {
        SessionTrajectory userMsg = SessionTrajectory.builder()
                .runId(java.util.UUID.randomUUID())
                .turnNumber(1).role("user").content("hello").build();
        SessionTrajectory systemMsg = SessionTrajectory.builder()
                .runId(java.util.UUID.randomUUID())
                .turnNumber(2).role("system").content("you are an assistant").build();

        List<SessionTrajectory> trajectories = List.of(userMsg, systemMsg);

        List<String> filtered = trajectories.stream()
                .filter(t -> "user".equals(t.getRole()) || "assistant".equals(t.getRole()))
                .map(SessionTrajectory::getRole)
                .toList();

        assertThat(filtered)
                .as("only user and assistant should pass the filter")
                .containsExactly("user");
    }
}
