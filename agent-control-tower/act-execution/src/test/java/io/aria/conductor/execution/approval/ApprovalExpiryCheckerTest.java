package io.aria.conductor.execution.approval;

import io.aria.conductor.common.event.ApprovalExpiredEvent;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalSource;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.test.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

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
    @Mock private AcpPermissionCoordinator acpPermissionCoordinator;
    @Mock private ApplicationEventPublisher eventPublisher;

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

        new ApprovalExpiryChecker(approvalRepository, approvalGate, acpPermissionCoordinator).checkExpiredApprovals();

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
    void checkExpiredApprovals_acpRowsDelegateToTheCoordinator_legacyRowsKeepTheGatePath() {
        UUID runId = UUID.randomUUID();
        Approval acp = TestDataBuilder.anApproval()
                .withRunId(runId)
                .withStatus(ApprovalStatus.PENDING)
                .withExpiresAt(Instant.now().minusSeconds(30))
                .build();
        acp.setSource(ApprovalSource.ACP_PERMISSION);
        Approval legacy = TestDataBuilder.anApproval()
                .withRunId(runId)
                .withStatus(ApprovalStatus.PENDING)
                .withExpiresAt(Instant.now().minusSeconds(30))
                .build();
        when(approvalRepository.findByStatusAndExpiresAtBefore(eq(ApprovalStatus.PENDING), any(Instant.class)))
                .thenReturn(List.of(acp, legacy));

        new ApprovalExpiryChecker(approvalRepository, approvalGate, acpPermissionCoordinator)
                .checkExpiredApprovals();

        // The ACP row belongs to the coordinator, which expires it atomically and delivers the
        // cancel itself: the sweep must neither mutate nor save it.
        verify(acpPermissionCoordinator).expire(acp.getId());
        assertThat(acp.getStatus()).isEqualTo(ApprovalStatus.PENDING);
        verify(approvalRepository, never()).save(acp);
        verify(approvalGate, never()).cancelPendingApproval(acp.getId());

        // Legacy rows keep the historical sweep behaviour.
        assertThat(legacy.getStatus()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(legacy.getReason()).isEqualTo("Auto-rejected: approval expired");
        verify(approvalRepository).save(legacy);
        verify(approvalGate).cancelPendingApproval(legacy.getId());
        verify(acpPermissionCoordinator, never()).expire(legacy.getId());
    }

    @Test
    void checkExpiredApprovals_noOverdueApprovals_performsNoWrites() {
        Instant before = Instant.now();
        when(approvalRepository.findByStatusAndExpiresAtBefore(any(ApprovalStatus.class), any(Instant.class)))
                .thenReturn(List.of());

        new ApprovalExpiryChecker(approvalRepository, approvalGate, acpPermissionCoordinator).checkExpiredApprovals();
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

        new ApprovalExpiryChecker(approvalRepository, approvalGate, acpPermissionCoordinator).checkExpiredApprovals();

        assertThat(fresh.getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(fresh.getReason()).isEqualTo("still fresh");
        assertThat(fresh.getDecidedAt()).isNull();
        verify(approvalGate, never()).cancelPendingApproval(fresh.getId());
    }

    // ── UX-6: the sweep must tell the operator the ask is gone ──────────────

    /**
     * UX-6 regression pin: the sweep used to expire the row silently — the operator only learned
     * an approval expired when it vanished from the queue. A legacy row expiring here must
     * publish an {@link ApprovalExpiredEvent} with the approval id, run id and the reason the
     * sweep recorded.
     */
    @Test
    void checkExpiredApprovals_legacyRow_expiryPublishesApprovalExpiredEvent() {
        UUID runId = UUID.randomUUID();
        Approval legacy = TestDataBuilder.anApproval()
                .withRunId(runId)
                .withStatus(ApprovalStatus.PENDING)
                .withReason("waiting on operator")
                .withExpiresAt(Instant.now().minusSeconds(60))
                .build();
        when(approvalRepository.findByStatusAndExpiresAtBefore(eq(ApprovalStatus.PENDING), any(Instant.class)))
                .thenReturn(List.of(legacy));

        new ApprovalExpiryChecker(approvalRepository, approvalGate, acpPermissionCoordinator, eventPublisher)
                .checkExpiredApprovals();

        ArgumentCaptor<ApprovalExpiredEvent> captor = ArgumentCaptor.forClass(ApprovalExpiredEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getApprovalId()).isEqualTo(legacy.getId());
        assertThat(captor.getValue().getRunId()).isEqualTo(runId);
        assertThat(captor.getValue().getReason()).isEqualTo("Auto-rejected: approval expired");
    }

    /**
     * UX-6: an ACP ask handed to the coordinator must surface the same expiry event, with the
     * reason the coordinator recorded — and only when the coordinator actually won the expiry.
     */
    @Test
    void checkExpiredApprovals_acpAskExpiredByCoordinator_publishesApprovalExpiredEvent() {
        UUID runId = UUID.randomUUID();
        Approval acp = TestDataBuilder.anApproval()
                .withRunId(runId)
                .withStatus(ApprovalStatus.PENDING)
                .withExpiresAt(Instant.now().minusSeconds(30))
                .build();
        acp.setSource(ApprovalSource.ACP_PERMISSION);
        when(approvalRepository.findByStatusAndExpiresAtBefore(eq(ApprovalStatus.PENDING), any(Instant.class)))
                .thenReturn(List.of(acp));
        when(acpPermissionCoordinator.expire(acp.getId())).thenReturn(true);

        new ApprovalExpiryChecker(approvalRepository, approvalGate, acpPermissionCoordinator, eventPublisher)
                .checkExpiredApprovals();

        ArgumentCaptor<ApprovalExpiredEvent> captor = ArgumentCaptor.forClass(ApprovalExpiredEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getApprovalId()).isEqualTo(acp.getId());
        assertThat(captor.getValue().getRunId()).isEqualTo(runId);
        assertThat(captor.getValue().getReason()).isEqualTo(AcpPermissionCoordinator.REASON_EXPIRED);
        // The coordinator-owned row is still never mutated or saved by the sweep.
        verify(approvalRepository, never()).save(acp);
        verify(approvalGate, never()).cancelPendingApproval(acp.getId());
    }

    /** A lost expiry race means the ask was decided, not expired — no expiry event then. */
    @Test
    void checkExpiredApprovals_acpExpiryLostRace_publishesNothing() {
        Approval acp = TestDataBuilder.anApproval()
                .withStatus(ApprovalStatus.PENDING)
                .withExpiresAt(Instant.now().minusSeconds(30))
                .build();
        acp.setSource(ApprovalSource.ACP_PERMISSION);
        when(approvalRepository.findByStatusAndExpiresAtBefore(eq(ApprovalStatus.PENDING), any(Instant.class)))
                .thenReturn(List.of(acp));
        when(acpPermissionCoordinator.expire(acp.getId())).thenReturn(false);

        new ApprovalExpiryChecker(approvalRepository, approvalGate, acpPermissionCoordinator, eventPublisher)
                .checkExpiredApprovals();

        verify(eventPublisher, never()).publishEvent(any(ApprovalExpiredEvent.class));
    }
}
