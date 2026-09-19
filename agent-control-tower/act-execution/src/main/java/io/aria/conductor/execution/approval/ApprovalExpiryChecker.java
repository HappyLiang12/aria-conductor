package io.aria.conductor.execution.approval;

import io.aria.conductor.common.event.ApprovalExpiredEvent;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalSource;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.execution.repository.ApprovalRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Scheduled checker that finds expired pending approvals,
 * marks them EXPIRED, and completes their futures with rejection.
 *
 * <p>{@code ACP_PERMISSION} rows (R10) are the ACP permission coordinator's property: the sweep
 * delegates them to {@link AcpPermissionCoordinator#expire(java.util.UUID)}, which expires the
 * row atomically and delivers the cancel to the ask's bridge session. It must not mutate or
 * save such a row itself — a state-only expiry would leave the sandbox waiting for a decision
 * the record says was already made.
 *
 * <p>UX-6: both branches announce the expiry through {@link ApprovalExpiredEvent} so the
 * operator learns the ask is gone instead of watching it silently vanish from the queue. A
 * lost expiry race (a decision landed first) is not an expiry and publishes nothing.
 */
@Slf4j
@Component
public class ApprovalExpiryChecker {

    /** Publisher for wirings/tests that never assert expiry notifications. */
    private static final ApplicationEventPublisher NO_OP_PUBLISHER = event -> { };

    private final ApprovalRepository approvalRepository;
    private final ApprovalGate approvalGate;
    private final AcpPermissionCoordinator acpPermissionCoordinator;
    private final ApplicationEventPublisher eventPublisher;

    public ApprovalExpiryChecker(ApprovalRepository approvalRepository, ApprovalGate approvalGate) {
        this(approvalRepository, approvalGate, null, NO_OP_PUBLISHER);
    }

    /**
     * No-op-publisher variant kept for manual wirings/tests that never assert expiry
     * notifications (the sweep behaves identically, just silently).
     *
     * @param acpPermissionCoordinator owner of {@code ACP_PERMISSION} rows (R10); {@code null}
     *                                 keeps the legacy-only sweep (manual wirings/tests)
     */
    public ApprovalExpiryChecker(ApprovalRepository approvalRepository, ApprovalGate approvalGate,
                                 AcpPermissionCoordinator acpPermissionCoordinator) {
        this(approvalRepository, approvalGate, acpPermissionCoordinator, NO_OP_PUBLISHER);
    }

    /**
     * @param acpPermissionCoordinator owner of {@code ACP_PERMISSION} rows (R10); {@code null}
     *                                 keeps the legacy-only sweep (manual wirings/tests)
     * @param eventPublisher           publishes {@link ApprovalExpiredEvent} on every expiry
     */
    @Autowired
    public ApprovalExpiryChecker(ApprovalRepository approvalRepository, ApprovalGate approvalGate,
                                 AcpPermissionCoordinator acpPermissionCoordinator,
                                 ApplicationEventPublisher eventPublisher) {
        this.approvalRepository = approvalRepository;
        this.approvalGate = approvalGate;
        this.acpPermissionCoordinator = acpPermissionCoordinator;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(fixedRate = 60000)
    @Transactional
    public void checkExpiredApprovals() {
        List<Approval> expired = approvalRepository.findByStatusAndExpiresAtBefore(
                ApprovalStatus.PENDING, Instant.now());

        if (expired.isEmpty()) return;

        log.info("Found {} expired pending approvals", expired.size());

        for (Approval approval : expired) {
            if (ApprovalSource.ACP_PERMISSION.equals(approval.getSource())) {
                expireAcpAsk(approval);
                continue;
            }
            log.warn("Expiring approval: id={}, runId={}", approval.getId(), approval.getRunId());
            approval.setStatus(ApprovalStatus.EXPIRED);
            approval.setReason("Auto-rejected: approval expired");
            approval.setDecidedAt(Instant.now());
            approvalRepository.save(approval);

            // Unblock any waiting thread with denial
            approvalGate.cancelPendingApproval(approval.getId());

            // UX-6: the operator must learn the ask expired, not just the blocked waiter.
            eventPublisher.publishEvent(new ApprovalExpiredEvent(
                    this, approval.getId(), approval.getRunId(), approval.getReason()));
        }
    }

    /**
     * R10: hand an overdue ACP ask to its owner. Without a coordinator the row stays PENDING —
     * a state-only expiry would lose the cancel the sandbox is still waiting for. The expiry
     * event is published only when the coordinator actually won the race: {@code false} means
     * the ask was already decided, which is not an expiry.
     */
    private void expireAcpAsk(Approval approval) {
        if (acpPermissionCoordinator == null) {
            log.warn("Overdue ACP approval {} has no coordinator wired — left PENDING for its owner",
                    approval.getId());
            return;
        }
        log.info("Expiring ACP approval {} through the ACP permission coordinator", approval.getId());
        if (acpPermissionCoordinator.expire(approval.getId())) {
            eventPublisher.publishEvent(new ApprovalExpiredEvent(
                    this, approval.getId(), approval.getRunId(), AcpPermissionCoordinator.REASON_EXPIRED));
        }
    }
}