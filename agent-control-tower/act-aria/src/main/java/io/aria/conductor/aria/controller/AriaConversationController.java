package io.aria.conductor.aria.controller;

import io.aria.conductor.agent.repository.AuditEventRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.aria.dto.ConversationSummary;
import io.aria.conductor.aria.dto.TimelineEntry;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.SessionTrajectory;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/aria/conversations")
public class AriaConversationController {

    private final RunRepository runRepository;
    private final SessionTrajectoryRepository trajectoryRepository;
    private final AuditEventRepository auditEventRepository;
    private final ToolCallRepository toolCallRepository;

    public AriaConversationController(RunRepository runRepository,
                                       SessionTrajectoryRepository trajectoryRepository,
                                       AuditEventRepository auditEventRepository,
                                       ToolCallRepository toolCallRepository) {
        this.runRepository = runRepository;
        this.trajectoryRepository = trajectoryRepository;
        this.auditEventRepository = auditEventRepository;
        this.toolCallRepository = toolCallRepository;
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

        // Pre-build runId → createdAt map for O(1) sort lookup
        Map<UUID, Instant> runCreated = runs.stream()
                .collect(Collectors.toMap(Run::getId, Run::getCreatedAt));

        // Sort globally by run creation time, then by turnNumber within each run
        List<TimelineEntry> timeline = trajectories.stream()
                .sorted(Comparator.comparing(
                        t -> runCreated.getOrDefault(t.getRunId(), t.getCreatedAt())))
                .map(t -> TimelineEntry.builder()
                        .role(t.getRole())
                        .content(t.getContent())
                        .timestamp(t.getCreatedAt())
                        .runId(t.getRunId().toString())
                        .build())
                .toList();

        return ResponseEntity.ok(timeline);
    }

    /**
     * Soft-delete a conversation: cancel all associated runs, then delete trajectories,
     * tool calls, and audit log entries. Returns 404 if no runs found for the conversationId.
     */
    @DeleteMapping("/{conversationId}")
    @Transactional
    public ResponseEntity<Void> deleteConversation(@PathVariable String conversationId) {
        List<Run> runs = runRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        if (runs.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        List<UUID> runIds = runs.stream().map(Run::getId).toList();

        // Soft-delete: cancel active runs; completed/failed runs are hard-deleted
        // along with their child data since trajectories and tool-calls will be removed.
        for (Run run : runs) {
            RunStatus status = run.getStatus();
            if (status == RunStatus.RUNNING || status == RunStatus.PENDING) {
                run.setStatus(RunStatus.CANCELLED);
                runRepository.save(run);
            }
        }

        // Delete child data
        toolCallRepository.deleteByRunIdIn(runIds);
        trajectoryRepository.deleteByRunIdIn(runIds);
        auditEventRepository.deleteByConversationId(conversationId);

        log.info("Conversation {} deleted: {} runs, {} runIds affected",
                conversationId, runs.size(), runIds.size());

        return ResponseEntity.noContent().build();
    }
}
