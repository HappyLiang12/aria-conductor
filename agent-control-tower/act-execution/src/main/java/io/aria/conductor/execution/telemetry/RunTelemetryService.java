package io.aria.conductor.execution.telemetry;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.SessionTrajectory;
import io.aria.conductor.common.model.ToolCall;
import io.aria.conductor.common.model.ToolCallStatus;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import io.aria.conductor.execution.security.CredentialRedactor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Persists the bridge's tool.call / tool.result / usage events into the same
 * {@code tool_calls} and {@code session_trajectory} rows the run drawer, the
 * approvals surface and the audit log read (F17), and records provider usage on
 * the run (F9).
 *
 * <p>Event identity is nullable per the cross-lane contract (the bridge lane is
 * still adding it). A present {@code toolCallId} yields deterministic row ids,
 * so a re-delivered event updates the same row instead of duplicating the audit
 * trail; an absent one still records what was observed.
 *
 * <p>Credential-shaped values are redacted before persistence: tool input is
 * attacker-influenced and must not become a durable secret store.
 *
 * <p>Wiring: {@code CoreExecutionService}'s event loop is the only producer. Its
 * telemetry adapter ({@code CoreEventTelemetry}) routes {@code tool.call},
 * {@code tool.result} and {@code usage} events here; every other event type
 * (permissions, prompt results, progress) is deliberately not recorded.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunTelemetryService {

    /** One {@code tool.call} event as the bridge contract defines it (fields nullable). */
    public record ToolCallEvent(String toolCallId, String toolName, String input, boolean isError) {
    }

    /** One {@code tool.result} event as the bridge contract defines it (fields nullable). */
    public record ToolResultEvent(String toolCallId, String toolName, String result, boolean isError) {
    }

    /** One {@code usage} event; every field may be null when the core did not report it. */
    public record UsageEvent(Long inputTokens, Long outputTokens, Long totalTokens) {
    }

    private final ToolCallRepository toolCalls;
    private final SessionTrajectoryRepository trajectories;
    private final RunRepository runs;

    /** Records a tool call and its trajectory row; returns the tool-call id. */
    @Transactional
    public UUID recordToolCall(UUID runId, ToolCallEvent event) {
        if (runId == null || event == null) {
            return null;
        }
        UUID toolCallId = toolCallRowId(runId, event.toolCallId());
        String input = CredentialRedactor.redact(event.input());
        Instant now = Instant.now();

        ToolCall call = toolCalls.findById(toolCallId).orElseGet(() -> ToolCall.builder()
                .id(toolCallId)
                .runId(runId)
                .toolName(toolName(event.toolName()))
                .status(ToolCallStatus.EXECUTING)
                .createdAt(now)
                .build());
        call.setToolName(toolName(event.toolName()));
        call.setArguments(input);
        call.setStatus(ToolCallStatus.EXECUTING);
        toolCalls.save(call);

        SessionTrajectory row = trajectoryRow(trajectories, trajectoryId(runId, event.toolCallId(), "call"));
        if (row == null) {
            row = SessionTrajectory.builder()
                    .id(trajectoryId(runId, event.toolCallId(), "call"))
                    .runId(runId)
                    .turnNumber(nextTurn(runId))
                    .role("tool")
                    .content("")
                    .createdAt(now)
                    .build();
        }
        row.setContent("tool_call: " + toolName(event.toolName())
                + (input == null || input.isBlank() ? "" : " " + input));
        row.setToolCalls(input);
        row.setToolCallId(event.toolCallId());
        trajectories.save(row);
        return toolCallId;
    }

    /** Records a tool result against the same row; returns the tool-call id. */
    @Transactional
    public UUID recordToolResult(UUID runId, ToolResultEvent event) {
        if (runId == null || event == null) {
            return null;
        }
        UUID toolCallId = toolCallRowId(runId, event.toolCallId());
        String result = CredentialRedactor.redact(event.result());
        Instant now = Instant.now();

        ToolCall call = toolCalls.findById(toolCallId).orElseGet(() -> ToolCall.builder()
                .id(toolCallId)
                .runId(runId)
                .toolName(toolName(event.toolName()))
                .createdAt(now)
                .build());
        call.setToolName(toolName(event.toolName()));
        call.setResult(result);
        call.setStatus(event.isError() ? ToolCallStatus.FAILED : ToolCallStatus.COMPLETED);
        if (call.getCreatedAt() != null) {
            call.setLatencyMs((int) Math.max(0, Duration.between(call.getCreatedAt(), now).toMillis()));
        }
        toolCalls.save(call);

        UUID trajectoryId = trajectoryId(runId, event.toolCallId(), "result");
        SessionTrajectory row = trajectoryRow(trajectories, trajectoryId);
        if (row == null) {
            row = SessionTrajectory.builder()
                    .id(trajectoryId)
                    .runId(runId)
                    .turnNumber(nextTurn(runId))
                    .role("tool")
                    .content("")
                    .createdAt(now)
                    .build();
        }
        row.setContent(result != null ? result : "");
        row.setToolCallId(event.toolCallId());
        trajectories.save(row);
        return toolCallId;
    }

    /**
     * Records provider usage on the run. An event with no reported field is a
     * no-op: unknown usage must stay unknown (never invented as zero). An
     * explicit provider total wins; otherwise the reported input+output is used.
     */
    @Transactional
    public void recordUsage(UUID runId, UsageEvent usage) {
        if (runId == null || usage == null) {
            return;
        }
        boolean reported = usage.inputTokens() != null
                || usage.outputTokens() != null
                || usage.totalTokens() != null;
        if (!reported) {
            return;
        }
        // Task 7 (spec 5.4): an all-zero report is the pinned CLI's placeholder shape --
        // a real LLM call cannot consume zero input tokens. Writing it would turn "not
        // reported" into a fabricated zero, and could overwrite a real measurement with
        // one, so the placeholder is ignored and the run stays NOT_REPORTED.
        if (isNullOrZero(usage.inputTokens()) && isNullOrZero(usage.outputTokens())
                && isNullOrZero(usage.totalTokens())) {
            log.debug("Ignoring an all-zero usage report for run {} (placeholder, not a measurement)", runId);
            return;
        }
        Run run = runs.findById(runId).orElse(null);
        if (run == null) {
            log.warn("Usage for unknown run {} was ignored", runId);
            return;
        }
        long input = usage.inputTokens() != null
                ? usage.inputTokens()
                : (run.getInputTokens() != null ? run.getInputTokens() : 0L);
        long output = usage.outputTokens() != null
                ? usage.outputTokens()
                : (run.getOutputTokens() != null ? run.getOutputTokens() : 0L);
        // A provider total wins when it is a real count. A carried zero next to
        // positive parts is the pinned CLI's placeholder shape, not a zero cost:
        // the backend is the party allowed to sum the parts.
        long reportedTotal = usage.totalTokens() != null ? usage.totalTokens() : 0L;
        long total = reportedTotal > 0 ? reportedTotal : input + output;
        // A single conditional UPDATE: this write never touches status, so a
        // concurrent cancel/stop cannot be reverted by a stale full-entity flush.
        int updated = runs.updateUsageIfNotTerminal(runId, usage.inputTokens(), usage.outputTokens(),
                total, Instant.now(), TERMINAL_STATUSES);
        if (updated == 0) {
            log.debug("Usage for terminal run {} was dropped", runId);
        }
    }

    /** The terminal statuses a usage write must never overwrite (mirrors the engine's set). */
    private static final List<RunStatus> TERMINAL_STATUSES = List.of(
            RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.ABORTED);

    /** An absent or zero report carries no measurement; only a positive count does. */
    private static boolean isNullOrZero(Long value) {
        return value == null || value == 0L;
    }

    /** Deterministic row id: a re-delivered event updates its own row. */
    private static UUID toolCallRowId(UUID runId, String toolCallId) {
        if (toolCallId == null || toolCallId.isBlank()) {
            return UUID.randomUUID();
        }
        return UUID.nameUUIDFromBytes(("tool-call:" + runId + ":" + toolCallId)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static UUID trajectoryId(UUID runId, String toolCallId, String role) {
        if (toolCallId == null || toolCallId.isBlank()) {
            return UUID.randomUUID();
        }
        return UUID.nameUUIDFromBytes(("tool-trajectory:" + role + ":" + runId + ":" + toolCallId)
                .getBytes(StandardCharsets.UTF_8));
    }

    private SessionTrajectory trajectoryRow(SessionTrajectoryRepository repository, UUID id) {
        return repository.findById(id).orElse(null);
    }

    private int nextTurn(UUID runId) {
        return trajectories.findMaxTurnNumberByRunId(runId) + 1;
    }

    private static String toolName(String toolName) {
        return toolName == null || toolName.isBlank() ? "unknown" : toolName;
    }
}
