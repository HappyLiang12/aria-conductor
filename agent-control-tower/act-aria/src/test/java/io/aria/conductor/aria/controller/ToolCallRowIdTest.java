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
 * Pins the one tool-call row-id derivation to the id the producer actually persists.
 *
 * <p>{@code ToolCall} has no raw-id column: its primary key is derived from
 * {@code (runId, raw tool_call_id)}, and the Aria restore path has to compute the same
 * value to join a trajectory row back to its {@code tool_calls} row. There is exactly one
 * derivation, {@link RunTelemetryService#toolCallRowId(UUID, String)}; this test drives the
 * real {@link RunTelemetryService} and asserts the row it writes carries that value, so a
 * producer that stops calling the shared function (or a second copy that reproduces a
 * different formula) fails here instead of silently detaching restored tool status from its
 * row.
 *
 * <p>The golden UUID pins the formula itself, not just the producer's use of it: a change to
 * the derivation is a change to every persisted {@code tool_calls} id, and must be a
 * deliberate, reviewed act rather than a quiet refactor.
 */
class ToolCallRowIdTest {

    private static final UUID RUN_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    /** {@code UUID.nameUUIDFromBytes("tool-call:" + RUN_ID + ":call-1")} as of this pin. */
    private static final UUID GOLDEN = UUID.fromString("41f7ab64-75e3-3cf8-9a3d-6c33341f3c16");

    @Test
    void thePersistedRowIdIsTheSharedDerivationAndTheDocumentedFormula() {
        ToolCallRepository toolCalls = mock(ToolCallRepository.class);
        SessionTrajectoryRepository trajectories = mock(SessionTrajectoryRepository.class);
        RunRepository runs = mock(RunRepository.class);
        RunTelemetryService telemetry = new RunTelemetryService(toolCalls, trajectories, runs);

        when(toolCalls.findById(any())).thenReturn(Optional.empty());
        when(trajectories.findById(any())).thenReturn(Optional.empty());
        when(trajectories.findMaxTurnNumberByRunId(RUN_ID)).thenReturn(0);

        telemetry.recordToolResult(RUN_ID,
                new RunTelemetryService.ToolResultEvent("call-1", "bash", "boom", true));

        ArgumentCaptor<ToolCall> saved = ArgumentCaptor.forClass(ToolCall.class);
        verify(toolCalls).save(saved.capture());
        assertThat(saved.getValue().getId())
                .isEqualTo(RunTelemetryService.toolCallRowId(RUN_ID, "call-1"))
                .isEqualTo(GOLDEN);
        assertThat(saved.getValue().getStatus()).isEqualTo(ToolCallStatus.FAILED);
    }

    /**
     * An absent raw id carries no identity to deduplicate on: the derivation must stay
     * per-event, otherwise unrelated tool calls would collide on one hash.
     */
    @Test
    void anAbsentRawToolCallIdIsNeverDeduplicated() {
        assertThat(RunTelemetryService.toolCallRowId(RUN_ID, null))
                .isNotEqualTo(RunTelemetryService.toolCallRowId(RUN_ID, null));
        assertThat(RunTelemetryService.toolCallRowId(RUN_ID, "call-1"))
                .isNotEqualTo(RunTelemetryService.toolCallRowId(RUN_ID, "call-2"));
    }
}
