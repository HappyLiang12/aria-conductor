package io.aria.conductor.execution.approval;

import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.test.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SDD regression pin (Task 11): the scheduled sweep in {@link ApprovalExpiryChecker} is a GENERIC
 * scan over every overdue PENDING approval — it must also expire SPEC_REVIEW approvals that the
 * spec-driven workflow raised (they carry {@code approvalType = SPEC_REVIEW} and have no
 * in-memory gate future). The spec-driven chain itself stays WAITING_APPROVAL: the checker only
 * marks the approval EXPIRED and notifies the gate, whose {@code cancelPendingApproval} is a
 * no-op for approvals without a future. The chain resumes via the user's resubmit-approval.
 */
@ExtendWith(MockitoExtension.class)
class ApprovalExpiryCheckerSddTest {

    @Mock private ApprovalRepository approvalRepository;
    @Mock private ApprovalGate approvalGate;

    @Test
    void expiredSpecReviewApproval_isMarkedExpired_andChainStaysWaiting() {
        Instant before = Instant.now();
        Approval specReview = TestDataBuilder.anApproval()
                .withApprovalType(Approval.ApprovalType.SPEC_REVIEW)
                .withStatus(ApprovalStatus.PENDING)
                .withReason("Spec review requested for development workflow")
                .withExpiresAt(Instant.now().minusSeconds(1))
                .build();
        when(approvalRepository.findByStatusAndExpiresAtBefore(eq(ApprovalStatus.PENDING), any(Instant.class)))
                .thenReturn(List.of(specReview));

        new ApprovalExpiryChecker(approvalRepository, approvalGate).checkExpiredApprovals();

        // The generic sweep expired the SPEC_REVIEW approval like any other overdue PENDING row.
        assertThat(specReview.getStatus()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(specReview.getReason()).isEqualTo("Auto-rejected: approval expired");
        assertThat(specReview.getDecidedAt()).isNotNull().isAfterOrEqualTo(before);

        // The mutated entity (not a copy) is persisted.
        verify(approvalRepository).save(argThat(x ->
                x.getId().equals(specReview.getId()) && x.getStatus() == ApprovalStatus.EXPIRED));

        // The gate is notified; for SPEC_REVIEW there is no in-memory future, so the gate's
        // cancelPendingApproval is a null-safe no-op (the checker never touches the workflow
        // chain — it stays WAITING_APPROVAL until the user re-requests via resubmit-approval).
        verify(approvalGate).cancelPendingApproval(specReview.getId());
    }
}
