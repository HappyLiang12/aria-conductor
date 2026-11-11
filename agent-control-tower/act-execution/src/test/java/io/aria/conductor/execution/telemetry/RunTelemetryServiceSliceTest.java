package io.aria.conductor.execution.telemetry;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.SessionTrajectory;
import io.aria.conductor.common.model.ToolCall;
import io.aria.conductor.common.model.ToolCallStatus;
import io.aria.conductor.execution.controller.RunToolCallController;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import io.aria.conductor.execution.runtime.RunInputCoordinator;
import io.aria.conductor.test.DataJpaTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F17/F9: bridge tool.call / tool.result / usage events must land in the same
 * {@code tool_calls} / {@code session_trajectory} rows the run drawer and audit
 * read, and provider usage must be recorded on the run without inventing zeros.
 *
 * <p>A real H2 slice, not a mock: the claims are about persisted rows and the
 * endpoint-level reads that expose them. The event-bus wiring in
 * {@code CoreExecutionService} belongs to the runtime lane; this test pins the
 * seam it will call.
 */
class RunTelemetryServiceSliceTest extends DataJpaTestBase {

    @Autowired private ToolCallRepository toolCalls;
    @Autowired private SessionTrajectoryRepository trajectories;
    @Autowired private RunRepository runs;

    private RunTelemetryService telemetry;
    private UUID runId;

    @BeforeEach
    void setUp() {
        telemetry = new RunTelemetryService(toolCalls, trajectories, runs);
        Run run = runs.save(Run.builder()
                .agentId(UUID.randomUUID())
                .status(RunStatus.RUNNING)
                .build());
        runId = run.getId();
    }

    @Test
    void toolCallEventPersistsAnExecutingToolCallAndATrajectoryRow() {
        UUID id = telemetry.recordToolCall(runId, new RunTelemetryService.ToolCallEvent(
                "tc-1", "Bash", "{\"command\":\"git status\"}", false));

        flushAndClear();
        ToolCall call = toolCalls.findById(id).orElseThrow();
        assertThat(call.getRunId()).isEqualTo(runId);
        assertThat(call.getToolName()).isEqualTo("Bash");
        assertThat(call.getArguments()).isEqualTo("{\"command\":\"git status\"}");
        assertThat(call.getStatus()).isEqualTo(ToolCallStatus.EXECUTING);

        List<SessionTrajectory> rows = trajectories.findByRunIdOrderByTurnNumberAsc(runId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getRole()).isEqualTo("tool");
        assertThat(rows.get(0).getToolCallId()).isEqualTo("tc-1");
        assertThat(rows.get(0).getContent()).contains("git status");
    }

    @Test
    void toolResultUpdatesTheSameRowAndAppendsATrajectoryEntry() {
        UUID id = telemetry.recordToolCall(runId, new RunTelemetryService.ToolCallEvent(
                "tc-1", "Bash", "{\"command\":\"git status\"}", false));
        telemetry.recordToolResult(runId, new RunTelemetryService.ToolResultEvent(
                "tc-1", "Bash", "clean", false));

        flushAndClear();
        ToolCall call = toolCalls.findById(id).orElseThrow();
        assertThat(call.getStatus()).isEqualTo(ToolCallStatus.COMPLETED);
        assertThat(call.getResult()).isEqualTo("clean");
        List<SessionTrajectory> rows = trajectories.findByRunIdOrderByTurnNumberAsc(runId);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).getContent()).isEqualTo("clean");
        assertThat(rows.get(1).getToolCallId()).isEqualTo("tc-1");
    }

    @Test
    void errorResultMarksTheToolCallFailed() {
        UUID id = telemetry.recordToolCall(runId, new RunTelemetryService.ToolCallEvent(
                "tc-2", "Bash", "{}", false));
        telemetry.recordToolResult(runId, new RunTelemetryService.ToolResultEvent(
                "tc-2", "Bash", "exit 1", true));

        flushAndClear();
        assertThat(toolCalls.findById(id).orElseThrow().getStatus()).isEqualTo(ToolCallStatus.FAILED);
    }

    @Test
    void redeliveredEventsAreIdempotent() {
        telemetry.recordToolCall(runId, new RunTelemetryService.ToolCallEvent(
                "tc-1", "Bash", "{}", false));
        telemetry.recordToolCall(runId, new RunTelemetryService.ToolCallEvent(
                "tc-1", "Bash", "{}", false));
        telemetry.recordToolResult(runId, new RunTelemetryService.ToolResultEvent(
                "tc-1", "Bash", "ok", false));
        telemetry.recordToolResult(runId, new RunTelemetryService.ToolResultEvent(
                "tc-1", "Bash", "ok", false));

        flushAndClear();
        assertThat(toolCalls.findByRunId(runId)).hasSize(1);
        assertThat(trajectories.findByRunIdOrderByTurnNumberAsc(runId)).hasSize(2);
    }

    @Test
    void credentialShapedToolInputIsRedactedBeforePersistence() {
        UUID id = telemetry.recordToolCall(runId, new RunTelemetryService.ToolCallEvent(
                "tc-3", "Bash", "{\"command\":\"git push https://ghp_abcdefghijklmnopqrstuvwxyz012345@github.com/o/r\"}",
                false));

        flushAndClear();
        ToolCall call = toolCalls.findById(id).orElseThrow();
        assertThat(call.getArguments()).doesNotContain("ghp_abcdefghijklmnopqrstuvwxyz012345");
        assertThat(call.getArguments()).contains("[REDACTED]");
        assertThat(trajectories.findByRunIdOrderByTurnNumberAsc(runId).get(0).getContent())
                .contains("[REDACTED]");
    }

    @Test
    void usageIsRecordedOnTheRunAndUnknownUsageIsNeverInvented() {
        telemetry.recordUsage(runId, new RunTelemetryService.UsageEvent(120L, 30L, null));
        flushAndClear();
        Run run = runs.findById(runId).orElseThrow();
        assertThat(run.getInputTokens()).isEqualTo(120L);
        assertThat(run.getOutputTokens()).isEqualTo(30L);
        assertThat(run.getTotalTokensUsed()).isEqualTo(150L);

        // An all-null usage event (the bridge could not observe it) changes nothing.
        telemetry.recordUsage(runId, new RunTelemetryService.UsageEvent(null, null, null));
        flushAndClear();
        assertThat(runs.findById(runId).orElseThrow().getInputTokens()).isEqualTo(120L);

        // An explicit provider total wins over the sum.
        telemetry.recordUsage(runId, new RunTelemetryService.UsageEvent(200L, 50L, 275L));
        flushAndClear();
        assertThat(runs.findById(runId).orElseThrow().getTotalTokensUsed()).isEqualTo(275L);
    }

    /**
     * F9: the pinned CLI records a placeholder total of 0 next to real input/output
     * counts; the backend is the party allowed to sum the parts.
     */
    @Test
    void aPlaceholderZeroTotalIsSummedFromTheReportedParts() {
        telemetry.recordUsage(runId, new RunTelemetryService.UsageEvent(120L, 30L, 0L));

        flushAndClear();
        assertThat(runs.findById(runId).orElseThrow().getTotalTokensUsed()).isEqualTo(150L);
    }

    /**
     * Task 7 / spec 5.4: the pinned CLI can report an entire all-zero usage block. A real
     * LLM call cannot consume zero input tokens, so that report is a placeholder, not a
     * measurement: it must not be written (a stored zero turns "not reported" into a
     * fabricated zero) and it must never overwrite a real measurement.
     */
    @Test
    void anAllZeroUsageReportIsIgnoredAndNeverOverwritesARealMeasurement() {
        // Positive control: a real report is stored.
        telemetry.recordUsage(runId, new RunTelemetryService.UsageEvent(120L, 30L, null));
        flushAndClear();

        telemetry.recordUsage(runId, new RunTelemetryService.UsageEvent(0L, 0L, 0L));
        flushAndClear();
        Run reported = runs.findById(runId).orElseThrow();
        assertThat(reported.getInputTokens()).isEqualTo(120L);
        assertThat(reported.getOutputTokens()).isEqualTo(30L);
        assertThat(reported.getTotalTokensUsed()).isEqualTo(150L);

        // A run whose only report is the placeholder keeps its columns null, so the run
        // response classifies it NOT_REPORTED instead of showing a zero cost.
        Run fresh = runs.save(Run.builder()
                .agentId(UUID.randomUUID())
                .status(RunStatus.RUNNING)
                .build());
        telemetry.recordUsage(fresh.getId(), new RunTelemetryService.UsageEvent(0L, 0L, 0L));
        flushAndClear();
        Run untouched = runs.findById(fresh.getId()).orElseThrow();
        assertThat(untouched.getInputTokens()).isNull();
        assertThat(untouched.getOutputTokens()).isNull();
        assertThat(untouched.getTotalTokensUsed()).isZero();
    }

    /**
     * F9 race: a usage event that arrives after the run finalized must not rewrite
     * lifecycle state (the old load-and-save flushed status/completedAt too).
     */
    @Test
    void usageForATerminalRunIsDroppedAndNeverRevertsItsStatus() {
        Run run = runs.findById(runId).orElseThrow();
        run.setStatus(RunStatus.COMPLETED);
        runs.save(run);
        flushAndClear();

        telemetry.recordUsage(runId, new RunTelemetryService.UsageEvent(10L, 5L, 15L));

        flushAndClear();
        Run reloaded = runs.findById(runId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(reloaded.getInputTokens()).isNull();
        assertThat(reloaded.getTotalTokensUsed()).isZero();
    }

    @Test
    void existingEndpointsReadThePersistedToolCallsAndTrajectory() {
        telemetry.recordToolCall(runId, new RunTelemetryService.ToolCallEvent(
                "tc-1", "Bash", "{\"command\":\"ls\"}", false));
        telemetry.recordToolResult(runId, new RunTelemetryService.ToolResultEvent(
                "tc-1", "Bash", "a.txt", false));
        flushAndClear();

        RunToolCallController controller = new RunToolCallController(
                toolCalls, trajectories, runs, new RunInputCoordinator(event -> { }));

        assertThat(controller.getToolCalls(runId).getBody()).hasSize(1);
        assertThat(controller.getToolCalls(runId).getBody().get(0).getToolName()).isEqualTo("Bash");
        assertThat(controller.getToolCalls(runId).getBody().get(0).getStatus()).isEqualTo("COMPLETED");
        assertThat(controller.getTrajectory(runId).getBody()).hasSize(2);
    }
}
