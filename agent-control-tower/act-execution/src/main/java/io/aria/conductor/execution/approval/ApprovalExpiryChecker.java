package io.aria.conductor.execution.approval;

import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalSource;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.execution.repository.ApprovalRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Scheduled checker that finds expired pending approvals,
 * marks them EXPIRED, and completes their futures with rejection.
 *
 * <p>Native ({@code ACP_PERMISSION}) asks are owned by the
 * {@link PermissionCoordinator}: this sweep skips them in its own loop and
 * hands their overdue rows to
 * {@link PermissionCoordinator#expireOverdueNativeAsks(Instant)}, so a native
 * ask is never settled by time silently (the coordinator moves the ledger
 * delivery state and publishes the expiry event). Its legacy behavior for
 * every other row is unchanged.
 */
@Slf4j
@Component
public class ApprovalExpiryChecker {

    private final ApprovalRepository approvalRepository;
    private final ApprovalGate approvalGate;
    private final PermissionCoordinator permissionCoordinator;

    public ApprovalExpiryChecker(ApprovalRepository approvalRepository, ApprovalGate approvalGate,
                                 PermissionCoordinator permissionCoordinator) {
        this.approvalRepository = approvalRepository;
        this.approvalGate = approvalGate;
        this.permissionCoordinator = permissionCoordinator;
    }

    @Scheduled(fixedRate = 60000)
    @Transactional
    public void checkExpiredApprovals() {
        Instant now = Instant.now();

        // Native asks settle through their own coordinator first — the same
        // adjudication the per-run deadline path uses — before the legacy
        // early-return below, so an empty legacy list never skips them.
        int settledNative = permissionCoordinator.expireOverdueNativeAsks(now);
        if (settledNative > 0) {
            log.info("Found {} expired native permission asks", settledNative);
        }

        List<Approval> expired = approvalRepository.findByStatusAndExpiresAtBefore(
                ApprovalStatus.PENDING, now);

        if (expired.isEmpty()) return;

        log.info("Found {} expired pending approvals", expired.size());

        for (Approval approval : expired) {
            if (approval.getSource() == ApprovalSource.ACP_PERMISSION) {
                // Owned by the permission coordinator and just adjudicated
                // through its own delivery-state scan; this legacy sweep never
                // rewrites it (the query is source-agnostic).
                continue;
            }
            log.warn("Expiring approval: id={}, runId={}", approval.getId(), approval.getRunId());
            approval.setStatus(ApprovalStatus.EXPIRED);
            approval.setReason(PermissionCoordinator.EXPIRY_REASON);
            approval.setDecidedAt(Instant.now());
            approvalRepository.save(approval);

            // Unblock any waiting thread with denial
            approvalGate.cancelPendingApproval(approval.getId());
        }
    }
}
