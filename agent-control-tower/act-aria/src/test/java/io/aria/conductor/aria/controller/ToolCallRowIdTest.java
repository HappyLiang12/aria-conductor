package io.aria.conductor.aria.controller;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.ToolCall;
import io.aria.conductor.common.model.ToolCallStatus;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import io.aria.conductor.execution.telemetry.RunTelemetryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins {@link ToolCallRowId} to the id the producer actually persists.
 *
 * <p>{@code ToolCall} has no raw-id column: its primary key is derived, and the
 * restore path re-states that derivation because the producer's copy is private.
 * This test drives the real {@link RunTelemetryService} and asserts the row it
 * writes carries {@link ToolCallRowId#of(UUID, String)} — so the two copies cannot
 * drift apart without a failure. If the equivalence ever needs to be enforced in
 * one place instead, expose {@code RunTelemetryService.toolCallRowId}.
 */
class ToolCallRowIdTest {

    @Test
    void matchesTheToolCallIdTheTelemetryServicePersists() {
        ToolCallRepository toolCalls = mock(ToolCallRepository.class);
        SessionTrajectoryRepository trajectories = mock(SessionTrajectoryRepository.class);
        RunRepository runs = mock(RunRepository.class);
        RunTelemetryService telemetry = new RunTelemetryService(toolCalls, trajectories, runs);

        UUID runId = UUID.randomUUID();
        when(toolCalls.findById(any())).thenReturn(Optional.empty());
        when(trajectories.findById(any())).thenReturn(Optional.empty());
        when(trajectories.findMaxTurnNumberByRunId(runId)).thenReturn(0);

        telemetry.recordToolResult(runId,
                new RunTelemetryService.ToolResultEvent("call-1", "bash", "boom", true));

        ArgumentCaptor<ToolCall> saved = ArgumentCaptor.forClass(ToolCall.class);
        verify(toolCalls).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(ToolCallRowId.of(runId, "call-1"));
        assertThat(saved.getValue().getStatus()).isEqualTo(ToolCallStatus.FAILED);
    }
}
