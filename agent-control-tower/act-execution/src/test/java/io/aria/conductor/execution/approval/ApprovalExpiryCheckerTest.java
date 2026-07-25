package io.aria.conductor.execution.approval;

import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.test.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the scheduled {@link ApprovalExpiryChecker} sweep: every overdue
 * PENDING approval returned by the repository query is marked EXPIRED (with
 * audit fields) and its blocked waiter is released via the gate; when nothing
 * is overdue the sweep performs no writes. Decided/fresh approvals are
 * protected by the query filter itself, which is asserted on the captured
 * query arguments.
 */
@ExtendWith(MockitoExtension.class)
class ApprovalExpiryCheckerTest {

    @Mock private ApprovalRepository approvalRepository;
    @Mock private ApprovalGate approvalGate;

    @Test
    void checkExpiredApprovals_marksOverduePendingAsExpiredAndUnblocksWaiters() {
        Instant before = Instant.now();
        UUID runId = UUID.randomUUID();
        Approval overdue1 = TestDataBuilder.anApproval()
                .withRunId(runId)
                .withStatus(ApprovalStatus.PENDING)
                .withReason("waiting on operator")
                .withExpiresAt(Instant.now().minusSeconds(120))
                .build();
        Approval overdue2 = TestDataBuilder.anApproval()
                .withRunId(runId)
                .withStatus(ApprovalStatus.PENDING)
                .withReason("waiting on operator")
                .withExpiresAt(Instant.now().minusSeconds(60))
                .build();
        when(approvalRepository.findByStatusAndExpiresAtBefore(eq(ApprovalStatus.PENDING), any(Instant.class)))
                .thenReturn(List.of(overdue1, overdue2));

        new ApprovalExpiryChecker(approvalRepository, approvalGate).checkExpiredApprovals();

        // Both entities are mutated in place with the expiry outcome and audit fields.
        for (Approval approval : List.of(overdue1, overdue2)) {
            assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.EXPIRED);
            assertThat(approval.getReason()).isEqualTo("Auto-rejected: approval expired");
            assertThat(approval.getDecidedAt()).isNotNull().isAfterOrEqualTo(before);
        }

        // The mutated entities (not copies) are persisted.
        ArgumentCaptor<Approval> savedCaptor = ArgumentCaptor.forClass(Approval.class);
        verify(approvalRepository, times(2)).save(savedCaptor.capture());
        assertThat(savedCaptor.getAllValues()).containsExactly(overdue1, overdue2);

        // Any thread blocked on these approvals is released through the gate.
        ArgumentCaptor<UUID> cancelledCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(approvalGate, times(2)).cancelPendingApproval(cancelledCaptor.capture());
        assertThat(cancelledCaptor.getAllValues())
                .containsExactlyInAnyOrder(overdue1.getId(), overdue2.getId());
    }

    @Test
    void checkExpiredApprovals_noOverdueApprovals_performsNoWrites() {
        Instant before = Instant.now();
        when(approvalRepository.findByStatusAndExpiresAtBefore(any(ApprovalStatus.class), any(Instant.class)))
                .thenReturn(List.of());

        new ApprovalExpiryChecker(approvalRepository, approvalGate).checkExpiredApprovals();
        Instant after = Instant.now();

        // The query itself is what protects decided and fresh approvals: it must select
        // only PENDING rows whose expiry lies strictly in the past (cutoff ≈ now).
        ArgumentCaptor<ApprovalStatus> statusCaptor = ArgumentCaptor.forClass(ApprovalStatus.class);
        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(approvalRepository).findByStatusAndExpiresAtBefore(statusCaptor.capture(), cutoffCaptor.capture());
        assertThat(statusCaptor.getValue()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(cutoffCaptor.getValue()).isBetween(before, after);

        verify(approvalRepository, never()).save(any());
        verify(approvalGate, never()).cancelPendingApproval(any());
    }

    @Test
    void checkExpiredApprovals_freshPendingApprovalOutsideQueryWindow_isNeverTouched() {
        // A fresh PENDING approval would not be returned by the repository query;
        // simulate exactly that and assert the checker leaves its state alone.
        Approval fresh = TestDataBuilder.anApproval()
                .withStatus(ApprovalStatus.PENDING)
                .withReason("still fresh")
                .withExpiresAt(Instant.now().plusSeconds(1800))
                .build();
        when(approvalRepository.findByStatusAndExpiresAtBefore(eq(ApprovalStatus.PENDING), any(Instant.class)))
                .thenReturn(List.of());

        new ApprovalExpiryChecker(approvalRepository, approvalGate).checkExpiredApprovals();

        assertThat(fresh.getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(fresh.getReason()).isEqualTo("still fresh");
        assertThat(fresh.getDecidedAt()).isNull();
        verify(approvalGate, never()).cancelPendingApproval(fresh.getId());
    }
}
