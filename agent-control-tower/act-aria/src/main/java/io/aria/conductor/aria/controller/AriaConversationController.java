package io.aria.conductor.aria.controller;

import io.aria.conductor.agent.repository.AuditEventRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.aria.dto.ConversationSummary;
import io.aria.conductor.aria.dto.TimelineEntry;
import io.aria.conductor.aria.repository.ConversationRunRepository;
import io.aria.conductor.aria.service.AriaService;
import io.aria.conductor.aria.service.RunFailureReason;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.SessionTrajectory;
import io.aria.conductor.common.model.ToolCall;
import io.aria.conductor.common.model.ToolCallStatus;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import io.aria.conductor.execution.security.OperatorAuthorityResolver;
import io.aria.conductor.execution.security.OperatorSessionService;
import io.aria.conductor.execution.telemetry.RunTelemetryService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/aria/conversations")
public class AriaConversationController {

    private final RunRepository runRepository;
    private final ConversationRunRepository conversationRunRepository;
    private final SessionTrajectoryRepository trajectoryRepository;
    private final AuditEventRepository auditEventRepository;
    private final ToolCallRepository toolCallRepository;
    private final AriaService ariaService;
    private final io.aria.conductor.agent.service.RunService runService;
    private final OperatorAuthorityResolver operatorAuthority;

    public AriaConversationController(RunRepository runRepository,
                                       ConversationRunRepository conversationRunRepository,
                                       SessionTrajectoryRepository trajectoryRepository,
                                       AuditEventRepository auditEventRepository,
                                       ToolCallRepository toolCallRepository,
                                       AriaService ariaService,
                                       io.aria.conductor.agent.service.RunService runService,
                                       OperatorAuthorityResolver operatorAuthority) {
        this.runRepository = runRepository;
        this.conversationRunRepository = conversationRunRepository;
        this.trajectoryRepository = trajectoryRepository;
        this.auditEventRepository = auditEventRepository;
        this.toolCallRepository = toolCallRepository;
        this.ariaService = ariaService;
        this.runService = runService;
        this.operatorAuthority = operatorAuthority;
    }

    /**
     * The same authority decision the other operator-gated controllers make: 403 for a worker
     * credential (or a forged cookie mutation), 401 with no verifiable identity. Null means the
     * caller is the operator; call this before any side effect.
     */
    private ResponseEntity<Object> rejectNonOperator(HttpServletRequest request, String action,
                                                     String surface) {
        try {
            operatorAuthority.resolveOperator(request, true);
        } catch (OperatorSessionService.ForbiddenMutationException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", refusal(action, surface)));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", refusal(action, surface)));
        }
        return null;
    }

    private static String refusal(String action, String surface) {
        return "Operator authority required: " + action + " is an operator decision. " + surface;
    }

    /**
     * Return the most recent conversation, or 204 if none exist.
     */
    @GetMapping("/latest")
    public ResponseEntity<ConversationSummary> getLatest() {
        List<Run> allRuns = runRepository.findAll();
        // Find the most recent run that has a non-null conversationId
        Run latest = allRuns.stream()
                .filter(r -> r.getConversationId() != null && !r.getConversationId().isBlank())
                .max(Comparator.comparing(Run::getCreatedAt))
                .orElse(null);

        if (latest == null) {
            return ResponseEntity.noContent().build();
        }

        String conversationId = latest.getConversationId();
        List<Run> conversationRuns = runRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);

        ConversationSummary summary = ConversationSummary.builder()
                .conversationId(conversationId)
                .lastMessageAt(latest.getCreatedAt())
                .runCount(conversationRuns.size())
                .build();

        return ResponseEntity.ok(summary);
    }

    /**
     * Return the full timeline of messages for a conversation, aggregated across all its runs.
     */
    @GetMapping("/{conversationId}")
    public ResponseEntity<List<TimelineEntry>> getTimeline(@PathVariable String conversationId) {
        List<Run> runs = runRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        if (runs.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }

        List<UUID> runIds = runs.stream().map(Run::getId).toList();
        List<SessionTrajectory> trajectories = trajectoryRepository
                .findByRunIdInOrderByTurnNumberAsc(runIds);

        // The repository returns trajectories globally ordered by turnNumber; grouping
        // preserves that within-run order for each run.
        Map<UUID, List<SessionTrajectory>> trajectoriesByRun = trajectories.stream()
                .collect(Collectors.groupingBy(SessionTrajectory::getRunId));

        // Assemble per run, in runs order (createdAt asc): a FAILED run's synthetic
        // error entry lands directly after that run's messages, never after a later run's.
        List<TimelineEntry> timeline = new ArrayList<>();
        for (Run run : runs) {
            // One read per run: the tool status of a restored tool row lives on its
            // tool_calls row, keyed by the deterministic id derived from the raw id.
            Map<UUID, ToolCallStatus> statusByRowId = new HashMap<>();
            for (ToolCall call : toolCallRepository.findByRunId(run.getId())) {
                statusByRowId.put(call.getId(), call.getStatus());
            }
            for (SessionTrajectory t : trajectoriesByRun.getOrDefault(run.getId(), List.of())) {
                String rawToolCallId = t.getToolCallId() != null && !t.getToolCallId().isBlank()
                        ? t.getToolCallId()
                        : null;
                // Only a present tool_calls row yields a status. A row with no matching
                // call leaves both fields absent rather than guessing a success. The row id
                // is the producer's own derivation, shared rather than re-stated.
                ToolCallStatus status = rawToolCallId != null
                        ? statusByRowId.get(RunTelemetryService.toolCallRowId(run.getId(), rawToolCallId))
                        : null;
                timeline.add(TimelineEntry.builder()
                        .role(t.getRole())
                        .content(t.getContent())
                        .timestamp(t.getCreatedAt())
                        .runId(t.getRunId().toString())
                        .toolCallId(rawToolCallId)
                        .toolStatus(status != null ? status.name() : null)
                        .build());
            }
            // A failed run that died before producing any trajectory would otherwise be
            // invisible in the timeline; add one synthetic error entry per FAILED run.
            if (run.getStatus() == RunStatus.FAILED) {
                timeline.add(TimelineEntry.builder()
                        .role("assistant")
                        .content("回合執行失敗：" + RunFailureReason.clip(run.getErrorMessage()))
                        .timestamp(run.getCompletedAt() != null ? run.getCompletedAt() : run.getUpdatedAt())
                        .runId(run.getId().toString())
                        .error(true)
                        .retryPrompt(run.getPromptSeed())
                        .build());
            }
        }

        return ResponseEntity.ok(timeline);
    }

    /**
     * Clear a conversation: cancel all its live runs, purge its transcript (trajectories,
     * tool calls, audit log entries), then detach every run of the conversation from it
     * ({@code conversation_id = null}). Returns 404 if no runs found for the conversationId.
     * <p>
     * The detach is what makes "Clear" true on every read surface: {@code /latest} picks
     * the newest run that still carries a conversation id, and the timeline resolves runs
     * by that id, so a conversation whose runs kept the id came back as the latest one --
     * a reload adopted it and drew an empty transcript under the old id. The run rows are
     * deliberately kept: they are the execution record the runs surface, usage totals and
     * reports read, and deleting them would have to purge eleven run-keyed tables (four
     * with a foreign key to {@code runs}) to avoid orphans. Only the conversation link,
     * which is what a conversation is, is forgotten.
     * <p>
     * T42 Item 4: this route purges history and cancels runs, and it resolved no authority at all,
     * so a run-scoped worker bearer was ignored and an anonymous remote caller was not refused
     * either. It is reachable from a sandbox because {@code RunMcpWiring} gives the core the same
     * host and port the REST API is served on. The same {@code resolveOperator} mechanism every
     * other destructive route uses now gates it, before the first read and before any side effect.
     * A worker bearer is refused 403 with the action named, so the operator never sees the surface
     * as "uniformly closed" while a worker can wipe a conversation.
     */
    @DeleteMapping("/{conversationId}")
    @Transactional
    public ResponseEntity<Object> deleteConversation(@PathVariable String conversationId,
                                                     HttpServletRequest request) {
        ResponseEntity<Object> refusal = rejectNonOperator(request,
                "deleting a conversation",
                "Deleting a conversation cancels its runs and destroys its history; do it from the "
                        + "dashboard as the local operator.");
        if (refusal != null) {
            return refusal;
        }
        List<Run> runs = runRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        if (runs.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        List<UUID> runIds = runs.stream().map(Run::getId).toList();

        // Cancel active runs through the verified cancel path first; nothing below may
        // run for a conversation whose stop was refused (the 409 propagates before any
        // purge). A terminal run is not relabelled here.
        for (Run run : runs) {
            RunStatus status = run.getStatus();
            if (status == RunStatus.RUNNING || status == RunStatus.PENDING
                    || status == RunStatus.WAITING_INPUT) {
                // #111: a live run may still be owned by this process's core, so the delete
                // must go through runService.cancelRun -- the only path that requests and
                // verifies the core stop before recording CANCELLED. A refused stop aborts
                // the delete with the mapped 409 before any child data is purged, instead of
                // relabelling a core that is still alive. A parked WAITING_INPUT run also
                // must be WOKEN, not just relabelled: the loop thread sits on the
                // coordinator future, so cancelRun publishes the RunCompletedEvent
                // (CANCELLED) that completes it.
                runService.cancelRun(run.getId());
            }
        }

        // Delete child data
        toolCallRepository.deleteByRunIdIn(runIds);
        trajectoryRepository.deleteByRunIdIn(runIds);
        auditEventRepository.deleteByConversationId(conversationId);

        // The conversation itself: a set-based UPDATE (never a load-and-save, which
        // would flush a status read before the verified cancels committed).
        conversationRunRepository.detachFromConversation(runIds);

        log.info("Conversation {} cleared: {} runs cancelled as needed, {} runIds detached",
                conversationId, runs.size(), runIds.size());

        return ResponseEntity.noContent().build();
    }

    /**
     * Compose (but never run) the one-click synthesis prompt for a completed dispatch
     * batch (Feature B4). With no body — or no {@code dispatchedByRunId} in it — the
     * conversation's latest dispatch group is used; an explicit id selects that group
     * directly. Returns 404 when nothing resolves (unknown conversation, or a group id
     * without children), 400 for a malformed UUID. The client sends the returned prompt
     * through the normal chat path.
     */
    @PostMapping("/{conversationId}/synthesize")
    public ResponseEntity<Map<String, String>> synthesize(
            @PathVariable String conversationId,
            @RequestBody(required = false) Map<String, String> body) {
        String rawDispatchedByRunId = body != null ? body.get("dispatchedByRunId") : null;
        UUID dispatchedByRunId = null;
        if (rawDispatchedByRunId != null && !rawDispatchedByRunId.isBlank()) {
            try {
                dispatchedByRunId = UUID.fromString(rawDispatchedByRunId.trim());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
        }
        return ariaService.composeSynthesisPrompt(conversationId, dispatchedByRunId)
                .map(prompt -> ResponseEntity.ok(Map.of("prompt", prompt)))
                .orElse(ResponseEntity.notFound().build());
    }
}
