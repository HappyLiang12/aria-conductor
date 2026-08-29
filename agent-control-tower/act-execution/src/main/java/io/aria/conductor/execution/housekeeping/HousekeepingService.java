package io.aria.conductor.execution.housekeeping;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.service.AgentService;
import io.aria.conductor.agent.service.RunService;
import io.aria.conductor.common.event.AuditLogEvent;
import io.aria.conductor.common.event.HousekeepingProgressEvent;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.approval.ApprovalGate;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.CategoryItem;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.CategoryReceipt;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.CategorySummary;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.Exclusions;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.HousekeepingReceipt;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.HousekeepingRequest;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.ScanResult;
import io.aria.conductor.execution.kanban.KanbanItem;
import io.aria.conductor.execution.kanban.KanbanRepository;
import io.aria.conductor.execution.kanban.KanbanService;
import io.aria.conductor.execution.kanban.KanbanStatus;
import io.aria.conductor.execution.repository.AgentSessionRepository;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.PromptCallRepository;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Housekeeping S2: operator-initiated cleanup of leftovers (terminal runs,
 * stuck/paused runs, finished kanban cards, leftover test agents, expired
 * approvals).
 *
 * <p>Safety contract:
 * <ul>
 *   <li>{@link #scan} is strictly read-only;</li>
 *   <li>{@link #execute} re-derives targets at execution time (never trusts a
 *       scan snapshot), is idempotent and single-flight;</li>
 *   <li>run purge deletes FK children before parents in chunks of
 *       {@value #CHUNK_SIZE} inside one transaction per chunk;</li>
 *   <li>per-item failures never abort the batch — they land in the receipt;</li>
 *   <li>progress is emitted per category; the final receipt is audited via
 *       {@link AuditLogEvent} (persisted + WS-broadcast for free).</li>
 * </ul>
 */
@Slf4j
@Service
public class HousekeepingService {

    static final int PREVIEW_LIMIT = 20;
    static final int CHUNK_SIZE = 200;
    private static final Duration RUN_MAX_AGE = Duration.ofHours(24);
    private static final Duration STUCK_MAX_AGE = Duration.ofMinutes(30);
    private static final Duration APPROVAL_MAX_AGE = Duration.ofHours(24);
    private static final Set<RunStatus> TERMINAL_RUNS = Set.of(
            RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.ABORTED);
    private static final Set<RunStatus> ACTIVE_RUNS = Set.of(
            RunStatus.RUNNING, RunStatus.PENDING, RunStatus.INITIALIZING, RunStatus.PAUSED);

    private final RunRepository runRepository;
    private final KanbanRepository kanbanRepository;
    private final AgentRepository agentRepository;
    private final ApprovalRepository approvalRepository;
    private final SessionTrajectoryRepository trajectoryRepository;
    private final ToolCallRepository toolCallRepository;
    private final PromptCallRepository promptCallRepository;
    private final AgentSessionRepository agentSessionRepository;
    private final KanbanService kanbanService;
    private final AgentService agentService;
    private final RunService runService;
    private final ApprovalGate approvalGate;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final AtomicBoolean executeInFlight = new AtomicBoolean();
    private final AtomicLong progressSeq = new AtomicLong();

    public HousekeepingService(RunRepository runRepository, KanbanRepository kanbanRepository,
                               AgentRepository agentRepository, ApprovalRepository approvalRepository,
                               SessionTrajectoryRepository trajectoryRepository,
                               ToolCallRepository toolCallRepository,
                               PromptCallRepository promptCallRepository,
                               AgentSessionRepository agentSessionRepository,
                               KanbanService kanbanService, AgentService agentService,
                               RunService runService, ApprovalGate approvalGate,
                               ApplicationEventPublisher eventPublisher,
                               TransactionTemplate transactionTemplate) {
        this.runRepository = runRepository;
        this.kanbanRepository = kanbanRepository;
        this.agentRepository = agentRepository;
        this.approvalRepository = approvalRepository;
        this.trajectoryRepository = trajectoryRepository;
        this.toolCallRepository = toolCallRepository;
        this.promptCallRepository = promptCallRepository;
        this.agentSessionRepository = agentSessionRepository;
        this.kanbanService = kanbanService;
        this.agentService = agentService;
        this.runService = runService;
        this.approvalGate = approvalGate;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = transactionTemplate;
    }

    /* ------------------------------------------------------------------ */
    /* Scan (read-only)                                                    */
    /* ------------------------------------------------------------------ */

    public ScanResult scan(boolean includeStuck, Exclusions exclusions) {
        Exclusions ex = exclusions != null ? exclusions : Exclusions.empty();
        Instant now = Instant.now();
        List<CategorySummary> categories = List.of(
                summary("runs", runsTargets(ex, now)),
                summary("stuck", includeStuck ? stuckTargets(ex, now) : List.of()),
                summary("kanban", kanbanTargets(ex)),
                summary("agents", agentsTargets(ex)),
                summary("approvals", approvalsTargets(ex, now)));
        return new ScanResult(categories, now);
    }

    private CategorySummary summary(String key, List<CategoryItem> items) {
        return new CategorySummary(key, items.size(),
                items.stream().limit(PREVIEW_LIMIT).toList());
    }

    private List<CategoryItem> runsTargets(Exclusions ex, Instant now) {
        Instant cutoff = now.minus(RUN_MAX_AGE);
        return runRepository.findByStatusIn(List.copyOf(TERMINAL_RUNS)).stream()
                .filter(r -> r.getCreatedAt() != null && r.getCreatedAt().isBefore(cutoff))
                .filter(r -> !ex.runIds().contains(r.getId().toString()))
                .sorted(Comparator.comparing(Run::getCreatedAt))
                .map(r -> new CategoryItem(r.getId().toString(),
                        abbreviate(r.getPromptSeed()), r.getStatus().name(), age(r.getCreatedAt(), now)))
                .toList();
    }

    private List<CategoryItem> stuckTargets(Exclusions ex, Instant now) {
        Instant cutoff = now.minus(STUCK_MAX_AGE);
        Set<String> pendingRunIds = approvalRepository.findByStatus(ApprovalStatus.PENDING).stream()
                .map(a -> a.getRunId().toString())
                .collect(java.util.stream.Collectors.toSet());
        return runRepository.findByStatus(RunStatus.PAUSED).stream()
                .filter(r -> updatedOrCreated(r).isBefore(cutoff))
                .filter(r -> pendingRunIds.contains(r.getId().toString()))
                .filter(r -> !ex.runIds().contains(r.getId().toString()))
                .map(r -> new CategoryItem(r.getId().toString(),
                        abbreviate(r.getPromptSeed()), r.getStatus().name(), age(updatedOrCreated(r), now)))
                .toList();
    }

    private List<CategoryItem> kanbanTargets(Exclusions ex) {
        return Stream.concat(
                        kanbanRepository.findByStatus(KanbanStatus.DONE).stream(),
                        kanbanRepository.findByStatus(KanbanStatus.CANCELLED).stream())
                .filter(k -> !ex.kanbanItemIds().contains(k.getId()))
                .map(k -> new CategoryItem(k.getId(), abbreviate(k.getTitle()),
                        k.getStatus().name(), ""))
                .toList();
    }

    private List<CategoryItem> agentsTargets(Exclusions ex) {
        return agentRepository.findAll().stream()
                .filter(a -> a.getHealthStatus() != HealthStatus.RETIRED)
                .filter(a -> (a.getName() != null && a.getName().startsWith("e2e-"))
                        || a.getHealthStatus() == HealthStatus.UNHEALTHY)
                .filter(a -> !ex.agentIds().contains(a.getId().toString()))
                .map(a -> new CategoryItem(a.getId().toString(), a.getName(),
                        a.getHealthStatus().name(), ""))
                .toList();
    }

    private List<CategoryItem> approvalsTargets(Exclusions ex, Instant now) {
        Instant cutoff = now.minus(APPROVAL_MAX_AGE);
        return approvalRepository.findByStatus(ApprovalStatus.PENDING).stream()
                .filter(a -> a.getRequestedAt() != null && a.getRequestedAt().isBefore(cutoff))
                .filter(a -> !runIsActive(a.getRunId()))
                .filter(a -> !ex.approvalIds().contains(a.getId().toString()))
                .map(a -> new CategoryItem(a.getId().toString(), abbreviate(a.getReason()),
                        a.getStatus().name(), age(a.getRequestedAt(), now)))
                .toList();
    }

    private boolean runIsActive(UUID runId) {
        return runRepository.findById(runId)
                .map(r -> ACTIVE_RUNS.contains(r.getStatus()))
                .orElse(false);
    }

    /* ------------------------------------------------------------------ */
    /* Execute (destructive, idempotent, single-flight)                    */
    /* ------------------------------------------------------------------ */

    public HousekeepingReceipt execute(HousekeepingRequest request) {
        if (request == null || !request.confirm()) {
            throw new IllegalArgumentException("Housekeeping execute requires explicit confirm");
        }
        if (!executeInFlight.compareAndSet(false, true)) {
            throw new IllegalStateException("Housekeeping execute already in flight");
        }
        try {
            return doExecute(request);
        } finally {
            executeInFlight.set(false);
        }
    }

    private HousekeepingReceipt doExecute(HousekeepingRequest request) {
        Exclusions ex = request.exclusions() != null ? request.exclusions() : Exclusions.empty();
        Instant now = Instant.now();
        List<CategoryReceipt> receipts = new ArrayList<>();
        // Cheap → expensive ordering; only selected categories run.
        if (request.categories().contains("kanban")) {
            receipts.add(processCategory("kanban", perItem(kanbanTargets(ex), id -> kanbanService.delete(id))));
        }
        if (request.categories().contains("agents")) {
            receipts.add(processCategory("agents", perItem(agentsTargets(ex),
                    id -> agentService.retireAgent(UUID.fromString(id)))));
        }
        if (request.categories().contains("approvals")) {
            receipts.add(processCategory("approvals", perItem(approvalsTargets(ex, now),
                    id -> approvalGate.decideApproval(UUID.fromString(id), false, "Expired by housekeeping"))));
        }
        if (request.categories().contains("stuck") && request.includeStuck()) {
            receipts.add(processCategory("stuck", perItem(stuckTargets(ex, now), id -> {
                runService.cancelRun(UUID.fromString(id));
                approvalGate.cancelAllPendingForRun(UUID.fromString(id));
            })));
        }
        if (request.categories().contains("runs")) {
            receipts.add(purgeRuns(runsTargets(ex, now)));
        }
        HousekeepingReceipt receipt = new HousekeepingReceipt(receipts, Instant.now());
        eventPublisher.publishEvent(new AuditLogEvent(this, "HOUSEKEEPING_EXECUTED",
                "Housekeeping", "batch", "EXECUTE", receiptToString(receipt), null));
        log.info("Housekeeping executed: {}", receiptToString(receipt));
        return receipt;
    }

    private interface ItemAction {
        void run(String id);
    }

    private record Counts(int cleared, int failed, int skipped) {
    }

    private CategoryReceipt processCategory(String key, Counts counts) {
        eventPublisher.publishEvent(new HousekeepingProgressEvent(this, key,
                counts.cleared(), counts.failed(), progressSeq.incrementAndGet()));
        return new CategoryReceipt(key, counts.cleared(), counts.failed(), counts.skipped());
    }

    private Counts perItem(List<CategoryItem> targets, ItemAction action) {
        int cleared = 0, failed = 0, skipped = 0;
        for (CategoryItem item : targets) {
            try {
                action.run(item.id());
                cleared++;
            } catch (ResourceNotFoundException | IllegalArgumentException e) {
                skipped++; // vanished between scan and execute — idempotent no-op
            } catch (Exception e) {
                failed++;
                log.warn("Housekeeping item failed: {} {} — {}", item.id(), item.title(), e.getMessage());
            }
        }
        return new Counts(cleared, failed, skipped);
    }

    private CategoryReceipt purgeRuns(List<CategoryItem> targets) {
        List<UUID> ids = targets.stream().map(t -> UUID.fromString(t.id())).toList();
        int cleared = 0, failed = 0;
        for (int i = 0; i < ids.size(); i += CHUNK_SIZE) {
            List<UUID> chunk = ids.subList(i, Math.min(ids.size(), i + CHUNK_SIZE));
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    // FK children first, parent last (no cascades in schema).
                    trajectoryRepository.deleteByRunIdInBulk(chunk);
                    toolCallRepository.deleteByRunIdInBulk(chunk);
                    promptCallRepository.deleteByRunIdInBulk(chunk);
                    approvalRepository.deleteByRunIdInBulk(chunk);
                    agentSessionRepository.deleteByRunIdInBulk(chunk);
                    runRepository.deleteByIdInBulk(chunk);
                });
                cleared += chunk.size();
            } catch (Exception e) {
                failed += chunk.size();
                log.warn("Housekeeping run purge chunk failed ({} runs): {}", chunk.size(), e.getMessage());
            }
        }
        return processCategory("runs", new Counts(cleared, failed, 0));
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                             */
    /* ------------------------------------------------------------------ */

    private Instant updatedOrCreated(Run run) {
        return run.getUpdatedAt() != null ? run.getUpdatedAt() : run.getCreatedAt();
    }

    private String age(Instant from, Instant now) {
        Duration d = Duration.between(from, now);
        return d.toHours() >= 1 ? d.toHours() + "h" : d.toMinutes() + "m";
    }

    private String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() > 80 ? flat.substring(0, 80) + "..." : flat;
    }

    private String receiptToString(HousekeepingReceipt receipt) {
        StringBuilder sb = new StringBuilder();
        for (CategoryReceipt r : receipt.categories()) {
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            sb.append(r.key()).append(": cleared=").append(r.cleared())
                    .append(" failed=").append(r.failed())
                    .append(" skipped=").append(r.skipped());
        }
        return sb.toString();
    }
}
