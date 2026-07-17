package io.aria.conductor.execution.approval;

import io.aria.conductor.common.model.Approval;
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
 */
@Slf4j
@Component
public class ApprovalExpiryChecker {

    private final ApprovalRepository approvalRepository;
    private final ApprovalGate approvalGate;

    public ApprovalExpiryChecker(ApprovalRepository approvalRepository, ApprovalGate approvalGate) {
        this.approvalRepository = approvalRepository;
        this.approvalGate = approvalGate;
    }

    @Scheduled(fixedRate = 60000)
    @Transactional
    public void checkExpiredApprovals() {
        List<Approval> expired = approvalRepository.findByStatusAndExpiresAtBefore(
                ApprovalStatus.PENDING, Instant.now());

        if (expired.isEmpty()) return;

        log.info("Found {} expired pending approvals", expired.size());

        for (Approval approval : expired) {
            log.warn("Expiring approval: id={}, runId={}", approval.getId(), approval.getRunId());
            approval.setStatus(ApprovalStatus.EXPIRED);
            approval.setReason("Auto-rejected: approval expired");
            approval.setDecidedAt(Instant.now());
            approvalRepository.save(approval);

            // Unblock any waiting thread with denial
            approvalGate.cancelPendingApproval(approval.getId());
        }
    }
}