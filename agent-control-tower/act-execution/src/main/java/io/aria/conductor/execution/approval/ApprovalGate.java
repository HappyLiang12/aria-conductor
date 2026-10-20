package io.aria.conductor.execution.approval;

import io.aria.conductor.common.event.ApprovalExpiredEvent;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalSource;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.ToolCallStatus;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Approval decisions and run-level cancellation.
 *
 * <p>The run-owned cores wait for a permission answer through the ACP
 * permission coordinator, not through a JVM thread blocked in this class;
 * this gate owns the shared decision bookkeeping (approval row, associated
 * tool call, decided event) and the run-end sweep.
 */
@Slf4j
@Component
public class ApprovalGate {

    private final ApprovalRepository approvalRepository;
    private final ToolCallRepository toolCallRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ApprovalGate(ApprovalRepository approvalRepository,
                        ToolCallRepository toolCallRepository,
                        ApplicationEventPublisher eventPublisher) {
        this.approvalRepository = approvalRepository;
        this.toolCallRepository = toolCallRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Process an approval decision — updates the entity and publishes the
     * decided event.
     *
     * <p>Operator authority is established by the caller (spec §6.2): the REST
     * boundary resolves the authenticated operator session before it calls here,
     * native permission asks are dispatched through
     * {@link PermissionCoordinator#decide(java.util.UUID, PermissionChoice, io.aria.conductor.common.security.ActorPrincipal)},
     * and the remaining callers are the platform's own sweeps (housekeeping
     * expiry/cancellation) rather than an external request. A decision for a
     * request that is no longer PENDING stays a no-op, preserving the existing
     * idempotent behaviour for repeated delivery. A {@code CLARIFICATION} ask is
     * refused outright (IllegalStateException, HTTP 409 at the REST boundary):
     * its answer lives on the ask row via {@code /approvals/{id}/answer}, and
     * run continuation is finalized via {@code /runs/{id}/finalize}.
     */
    @Transactional
    public void decideApproval(UUID approvalId, boolean approved, String reason) {
        log.info("Processing approval decision: approvalId={}, approved={}", approvalId, approved);

        Approval approval = approvalRepository.findById(approvalId)
                .orElseThrow(() -> new IllegalArgumentException("Approval not found: " + approvalId));

        // Plan B task 2: CLARIFICATION asks are answered via /approvals/{id}/answer or the run
        // finalize path — the legacy decide flow must never record an approve/deny for them
        // (the controller surfaces this refusal as HTTP 409 on /decide).
        if (approval.getSource() == ApprovalSource.CLARIFICATION) {
            throw new IllegalStateException(
                    "CLARIFICATION asks are answered via /approvals/{id}/answer or finalized via /runs/{id}/finalize");
        }

        if (approval.getStatus() != ApprovalStatus.PENDING) {
            log.warn("Approval {} is already in status {}, ignoring decision", approvalId, approval.getStatus());
            return;
        }

        approval.setStatus(approved ? ApprovalStatus.APPROVED : ApprovalStatus.DENIED);
        approval.setReason(reason);
        approval.setDecidedAt(Instant.now());
        approvalRepository.save(approval);

        if (approval.getToolCallId() != null) {
            toolCallRepository.findById(approval.getToolCallId()).ifPresent(tc -> {
                tc.setStatus(approved ? ToolCallStatus.EXECUTING : ToolCallStatus.DENIED);
                toolCallRepository.save(tc);
            });
        }

        eventPublisher.publishEvent(new io.aria.conductor.common.event.ApprovalDecidedEvent(
                this, approvalId, approval.getStatus()));
    }

    /**
     * Cancel all pending approvals for a given run.
     *
     * <p>Only TOOL_CALL approvals are cancelled: SPEC_REVIEW approvals are created after
     * the BA run has already completed (by {@code SpecReviewCoordinator}) and their
     * lifecycle is governed by the spec-approval flow, not the originating run.
     *
     * <p>R20.5: ACP permission asks are likewise left alone — their lifecycle belongs to
     * {@code AcpPermissionCoordinator.cancelPendingForRun}. CLARIFICATION asks are left
     * alone for the same ownership reason: the run-input lifecycle settles them
     * (the completion sweep, restart adjudication), never this legacy broad sweep.
     *
     * <p>REVIEW_REQUEST asks are display-only post-run prompts (the review card the
     * operator answers after completion); they never block the run and are not
     * cancelled by its end.
     *
     * <p>Each settled ask is an expiry, not a silent lapse: an
     * {@link ApprovalExpiredEvent} is emitted after the row is saved, so the request
     * notification flips and the operator gets an expiry notice. Listeners run
     * synchronously here (this method is non-transactional), and the native-only
     * card-settle listener no-ops for these legacy rows.
     */
    public void cancelAllPendingForRun(UUID runId) {
        approvalRepository.findByRunId(runId).stream()
                .filter(a -> a.getStatus() == ApprovalStatus.PENDING)
                .filter(a -> a.getSource() != ApprovalSource.ACP_PERMISSION)
                .filter(a -> a.getSource() != ApprovalSource.CLARIFICATION)
                .filter(a -> a.getAskType() != Approval.AskType.REVIEW_REQUEST)
                .filter(a -> a.getApprovalType() == null
                        || a.getApprovalType() == Approval.ApprovalType.TOOL_CALL)
                .forEach(a -> {
                    a.setStatus(ApprovalStatus.EXPIRED);
                    a.setReason("Run cancelled");
                    a.setDecidedAt(Instant.now());
                    approvalRepository.save(a);
                    eventPublisher.publishEvent(new ApprovalExpiredEvent(
                            this, a.getId(), a.getRunId(), a.getReason()));
                });
    }
}
