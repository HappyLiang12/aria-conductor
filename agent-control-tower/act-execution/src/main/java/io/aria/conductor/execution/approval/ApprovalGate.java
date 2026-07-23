package io.aria.conductor.execution.approval;

import io.aria.conductor.common.event.ApprovalRequestedEvent;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.ToolCall;
import io.aria.conductor.common.model.ToolCallStatus;
import io.aria.conductor.execution.engine.RunContext;
import io.aria.conductor.execution.pipeline.Action;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Approval gate — blocks agent loop until approval is granted or timeout expires.
 * Uses CompletableFuture to block virtual threads without blocking OS threads.
 */
@Slf4j
@Component
public class ApprovalGate {

    private static final long APPROVAL_TIMEOUT_MINUTES = 30;

    private final ApprovalRepository approvalRepository;
    private final ToolCallRepository toolCallRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Map<UUID, CompletableFuture<ApprovalDecision>> pendingApprovals = new ConcurrentHashMap<>();

    public ApprovalGate(ApprovalRepository approvalRepository,
                        ToolCallRepository toolCallRepository,
                        ApplicationEventPublisher eventPublisher) {
        this.approvalRepository = approvalRepository;
        this.toolCallRepository = toolCallRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Stage-4 helper: request a SINGLE batched approval covering every action a turn
     * wants to execute. The operator sees one prompt of the form
     * <pre>"Agent wants to execute N actions: [a1, a2, ...]. Approve All / Deny All / Review Each"</pre>
     * and one decision unblocks (or rejects) all of them.
     *
     * <p>If {@code turnActions} is null/empty the call is treated as auto-approved (no-op).
     * If {@code turnActions} contains exactly one action, this falls through to
     * {@link #requestApproval(Action, RunContext)} so single-action behaviour is unchanged.
     */
    public ApprovalDecision requestTurnApproval(RunContext ctx, List<Action> turnActions) {
        if (turnActions == null || turnActions.isEmpty()) {
            return ApprovalDecision.approve("No actions requiring approval");
        }
        if (turnActions.size() == 1) {
            return requestApproval(turnActions.get(0), ctx);
        }

        UUID approvalId = UUID.randomUUID();
        UUID firstToolCallId = null;
        StringBuilder names = new StringBuilder();

        // Find existing ToolCall entities (created by caller before invoking this method)
        List<UUID> existingIds = ctx.getCurrentTurnToolCallIds();
        if (existingIds != null && existingIds.size() == turnActions.size()) {
            for (int i = 0; i < turnActions.size(); i++) {
                Action action = turnActions.get(i);
                UUID tcId = existingIds.get(i);
                var found = toolCallRepository.findById(tcId);
                if (found.isPresent()) {
                    var tc = found.get();
                    if (tc.getStatus() != ToolCallStatus.COMPLETED && tc.getStatus() != ToolCallStatus.FAILED) {
                        tc.setStatus(ToolCallStatus.PENDING);
                        toolCallRepository.save(tc);
                    }
                } else {
                    log.warn("ToolCall {} not found in DB during requestTurnApproval — approval may reference orphaned ID", tcId);
                }
                if (firstToolCallId == null) {
                    firstToolCallId = tcId;
                }
                if (i > 0) names.append(", ");
                names.append(action.name());
            }
        } else {
            // Fallback: create ToolCall entities if not pre-created by caller
            for (int i = 0; i < turnActions.size(); i++) {
                Action action = turnActions.get(i);
                ToolCall toolCall = ToolCall.builder()
                        .runId(ctx.getRunId())
                        .toolName(action.name())
                        .arguments(action.arguments())
                        .status(ToolCallStatus.PENDING)
                        .build();
                ToolCall savedToolCall = toolCallRepository.save(toolCall);
                if (firstToolCallId == null) {
                    firstToolCallId = savedToolCall.getId();
                }
                if (i > 0) names.append(", ");
                names.append(action.name());
            }
        }

        Instant now = Instant.now();
        String batchedReason = String.format(
                "Agent wants to execute %d actions: [%s]. Approve All / Deny All / Review Each",
                turnActions.size(), names);
        Approval approval = Approval.builder()
                .runId(ctx.getRunId())
                .toolCallId(firstToolCallId)
                .status(ApprovalStatus.PENDING)
                .reason(batchedReason)
                .expiresAt(now.plusSeconds(APPROVAL_TIMEOUT_MINUTES * 60))
                .build();
        approvalRepository.save(approval);

        CompletableFuture<ApprovalDecision> future = new CompletableFuture<>();
        pendingApprovals.put(approval.getId(), future);

        eventPublisher.publishEvent(new ApprovalRequestedEvent(
                this, approval.getId(), ctx.getRunId(), firstToolCallId));

        log.info("Turn approval requested: approvalId={}, runId={}, actions=[{}], expiresAt={}",
                approval.getId(), ctx.getRunId(),
                turnActions.stream().map(Action::name).collect(Collectors.joining(",")),
                approval.getExpiresAt());

        try {
            long awaitStart = System.currentTimeMillis();
            ApprovalDecision decision = future.get(APPROVAL_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            ctx.addBlockedWait(java.time.Duration.ofMillis(System.currentTimeMillis() - awaitStart));
            return decision;
        } catch (TimeoutException e) {
            ctx.addBlockedWait(java.time.Duration.ofMinutes(APPROVAL_TIMEOUT_MINUTES));
            log.warn("Turn approval timed out: approvalId={}", approvalId);
            handleTimeout(approval.getId());
            return ApprovalDecision.deny(
                    "Approval request timed out after " + APPROVAL_TIMEOUT_MINUTES + " minutes");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Turn approval interrupted: approvalId={}", approvalId);
            cancelPendingApproval(approval.getId());
            return ApprovalDecision.deny("Approval request interrupted");
        } catch (Exception e) {
            log.error("Turn approval failed unexpectedly: approvalId={}", approvalId, e);
            cancelPendingApproval(approval.getId());
            return ApprovalDecision.deny("Approval request failed: " + e.getMessage());
        }
    }

    /**
     * Request approval for an action. Blocks the virtual thread until:
     * - Approval is granted/denied via decideApproval()
     * - Timeout expires (auto-reject)
     */
    public ApprovalDecision requestApproval(Action action, RunContext ctx) {
        return requestApproval(action, ctx, null);
    }

    /**
     * Request approval for an action, optionally with an escalation note explaining WHY the action
     * was routed to a human (e.g. AI self-verification reasoning). Blocks the virtual thread until
     * the approval is decided or the timeout expires.
     */
    public ApprovalDecision requestApproval(Action action, RunContext ctx, String escalationNote) {
        UUID approvalId = UUID.randomUUID();

        // Find existing ToolCall entity (created by AgentLoopEngine before pipeline execution),
        // or create one if no pre-existing entity is found (backward compatibility).
        UUID toolCallId = ctx.getCurrentToolCallId();
        if (toolCallId == null) {
            ToolCall toolCall = ToolCall.builder()
                    .runId(ctx.getRunId())
                    .toolName(action.name())
                    .arguments(action.arguments())
                    .status(ToolCallStatus.PENDING)
                    .build();
            toolCallId = toolCallRepository.save(toolCall).getId();
        }

        // Create Approval entity
        Instant now = Instant.now();
        Approval approval = Approval.builder()
                .runId(ctx.getRunId())
                .toolCallId(toolCallId)
                .status(ApprovalStatus.PENDING)
                .reason(buildApprovalReason(action, escalationNote))
                .expiresAt(now.plusSeconds(APPROVAL_TIMEOUT_MINUTES * 60))
                .build();
        approvalRepository.save(approval);

        // Create CompletableFuture to block this virtual thread
        CompletableFuture<ApprovalDecision> future = new CompletableFuture<>();
        pendingApprovals.put(approval.getId(), future);

        // Publish event for dashboard notifications
        eventPublisher.publishEvent(new ApprovalRequestedEvent(
                this, approval.getId(), ctx.getRunId(), toolCallId));

        log.info("Approval requested: approvalId={}, runId={}, action={}, expiresAt={}",
                approval.getId(), ctx.getRunId(), action.name(), approval.getExpiresAt());

        // Block until decision or timeout
        try {
            long awaitStart = System.currentTimeMillis();
            ApprovalDecision decision = future.get(APPROVAL_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            ctx.addBlockedWait(java.time.Duration.ofMillis(System.currentTimeMillis() - awaitStart));
            return decision;
        } catch (TimeoutException e) {
            ctx.addBlockedWait(java.time.Duration.ofMinutes(APPROVAL_TIMEOUT_MINUTES));
            log.warn("Approval timed out: approvalId={}", approvalId);
            handleTimeout(approval.getId());
            return ApprovalDecision.deny("Approval request timed out after " + APPROVAL_TIMEOUT_MINUTES + " minutes");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Approval interrupted: approvalId={}", approvalId);
            cancelPendingApproval(approval.getId());
            return ApprovalDecision.deny("Approval request interrupted");
        } catch (Exception e) {
            log.error("Approval failed unexpectedly: approvalId={}", approvalId, e);
            cancelPendingApproval(approval.getId());
            return ApprovalDecision.deny("Approval request failed: " + e.getMessage());
        }
    }

    /**
     * Process an approval decision — updates entity and unblocks the waiting virtual thread.
     */
    @Transactional
    public void decideApproval(UUID approvalId, boolean approved, String reason) {
        log.info("Processing approval decision: approvalId={}, approved={}", approvalId, approved);

        Approval approval = approvalRepository.findById(approvalId)
                .orElseThrow(() -> new IllegalArgumentException("Approval not found: " + approvalId));

        if (approval.getStatus() != ApprovalStatus.PENDING) {
            log.warn("Approval {} is already in status {}, ignoring decision", approvalId, approval.getStatus());
            return;
        }

        // Update approval entity
        approval.setStatus(approved ? ApprovalStatus.APPROVED : ApprovalStatus.DENIED);
        approval.setReason(reason);
        approval.setDecidedAt(Instant.now());
        approvalRepository.save(approval);

        // Update associated tool call
        if (approval.getToolCallId() != null) {
            toolCallRepository.findById(approval.getToolCallId()).ifPresent(tc -> {
                tc.setStatus(approved ? ToolCallStatus.EXECUTING : ToolCallStatus.DENIED);
                toolCallRepository.save(tc);
            });
        }

        // Unblock the waiting virtual thread
        CompletableFuture<ApprovalDecision> future = pendingApprovals.remove(approvalId);
        if (future != null && !future.isDone()) {
            ApprovalDecision decision = approved
                    ? ApprovalDecision.approve(reason)
                    : ApprovalDecision.deny(reason);
            future.complete(decision);
        }

        // Publish event
        eventPublisher.publishEvent(new io.aria.conductor.common.event.ApprovalDecidedEvent(
                this, approvalId, approval.getStatus()));
    }

    /**
     * Build a human-readable approval reason that surfaces the tool name and a truncated
     * arguments preview, so operators can make an informed decision in the UI (#24).
     */
    private String buildApprovalReason(Action action, String escalationNote) {
        String args = action.arguments() != null ? action.arguments() : "";
        if (args.length() > 200) {
            args = args.substring(0, 200) + "…";
        }
        String base = "Agent requests approval to execute " + action.name() + (args.isBlank() ? "" : " " + args);
        if (escalationNote != null && !escalationNote.isBlank()) {
            return "AI escalated for human review: " + escalationNote.trim() + " — " + base;
        }
        return base;
    }

    /**
     * Handle approval timeout — mark as expired and reject.
     */
    private void handleTimeout(UUID approvalId) {
        try {
            approvalRepository.findById(approvalId).ifPresent(approval -> {
                approval.setStatus(ApprovalStatus.EXPIRED);
                approval.setReason("Auto-rejected: approval timed out");
                approval.setDecidedAt(Instant.now());
                approvalRepository.save(approval);
            });
        } catch (Exception e) {
            log.error("Failed to mark approval {} as expired", approvalId, e);
        }
        pendingApprovals.remove(approvalId);
    }

    /**
     * Cancel a pending approval (e.g. when run is cancelled).
     */
    public void cancelPendingApproval(UUID approvalId) {
        CompletableFuture<ApprovalDecision> future = pendingApprovals.remove(approvalId);
        if (future != null && !future.isDone()) {
            future.complete(ApprovalDecision.deny("Run cancelled"));
        }
    }

    /**
     * Cancel all pending approvals for a given run.
     */
    public void cancelAllPendingForRun(UUID runId) {
        approvalRepository.findByRunId(runId).stream()
                .filter(a -> a.getStatus() == ApprovalStatus.PENDING)
                .forEach(a -> {
                    a.setStatus(ApprovalStatus.EXPIRED);
                    a.setReason("Run cancelled");
                    a.setDecidedAt(Instant.now());
                    approvalRepository.save(a);
                    cancelPendingApproval(a.getId());
                });
    }
}