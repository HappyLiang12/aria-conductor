package io.aria.conductor.execution.approval;

import io.aria.conductor.common.event.ApprovalExpiredEvent;
import io.aria.conductor.common.model.AcpPermissionRequest;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalSource;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.repository.AcpPermissionRequestRepository;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.execution.mcp.PlatformMcpAutoApproval;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import io.aria.conductor.test.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
 * query arguments. Native ({@code ACP_PERMISSION}) rows are owned by the
 * {@link PermissionCoordinator}: the sweep skips them in its own loop and
 * settles their overdue asks through the coordinator (R-RFUX2), so no native
 * ask is ever settled silently.
 */
@ExtendWith(MockitoExtension.class)
class ApprovalExpiryCheckerTest {

    @Mock private ApprovalRepository approvalRepository;
    @Mock private ApprovalGate approvalGate;
    @Mock private PermissionCoordinator permissionCoordinator;
    @Mock private ApprovalDecisionLockRepository decisionLocks;
    @Mock private AcpPermissionRequestRepository permissionRepository;
    @Mock private ToolCallRepository toolCallRepository;
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

        new ApprovalExpiryChecker(approvalRepository, approvalGate, permissionCoordinator)
                .checkExpiredApprovals();

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

        new ApprovalExpiryChecker(approvalRepository, approvalGate, permissionCoordinator)
                .checkExpiredApprovals();
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

        new ApprovalExpiryChecker(approvalRepository, approvalGate, permissionCoordinator)
                .checkExpiredApprovals();

        assertThat(fresh.getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(fresh.getReason()).isEqualTo("still fresh");
        assertThat(fresh.getDecidedAt()).isNull();
        verify(approvalGate, never()).cancelPendingApproval(fresh.getId());
    }

    /**
     * R-RFUX2: the legacy query is source-agnostic, but a native row is never
     * settled silently by the checker's own loop — the sweep skips
     * {@code ACP_PERMISSION} rows and delegates every overdue native ask to the
     * coordinator (their single owner), while a legacy row keeps the checker's
     * own behavior untouched.
     */
    @Test
    void checkExpiredApprovals_skipsNativeRowsInItsOwnLoopAndDelegatesThemToTheCoordinator() {
        Instant before = Instant.now();
        Approval legacy = TestDataBuilder.anApproval()
                .withStatus(ApprovalStatus.PENDING)
                .withReason("waiting on operator")
                .withExpiresAt(Instant.now().minusSeconds(60))
                .build();
        Approval nativeAsk = TestDataBuilder.anApproval()
                .withStatus(ApprovalStatus.PENDING)
                .withReason("Native permission request 0 from session ses_fixture_1 for tool run_agent (NATIVE_TOOL)")
                .withExpiresAt(Instant.now().minusSeconds(30))
                .build();
        nativeAsk.setSource(ApprovalSource.ACP_PERMISSION);
        when(approvalRepository.findByStatusAndExpiresAtBefore(eq(ApprovalStatus.PENDING), any(Instant.class)))
                .thenReturn(List.of(legacy, nativeAsk));
        when(permissionCoordinator.expireOverdueNativeAsks(any(Instant.class))).thenReturn(1);

        new ApprovalExpiryChecker(approvalRepository, approvalGate, permissionCoordinator)
                .checkExpiredApprovals();
        Instant after = Instant.now();

        // The legacy row is settled by the checker exactly as before ...
        assertThat(legacy.getStatus()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(legacy.getReason()).isEqualTo("Auto-rejected: approval expired");
        verify(approvalGate).cancelPendingApproval(legacy.getId());
        // ... the native row is left to the coordinator alone — never touched here.
        assertThat(nativeAsk.getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(nativeAsk.getDecidedAt()).isNull();
        verify(approvalGate, never()).cancelPendingApproval(nativeAsk.getId());
        // The coordinator sweep ran with the sweep's own instant.
        ArgumentCaptor<Instant> asOf = ArgumentCaptor.forClass(Instant.class);
        verify(permissionCoordinator).expireOverdueNativeAsks(asOf.capture());
        assertThat(asOf.getValue()).isBetween(before, after);
    }

    /**
     * R-RFUX2 end-to-end: an overdue native ask is settled through the real
     * coordinator path — EXPIRED with the recorded expiry reason, ledger
     * delivery EXPIRED, {@link ApprovalExpiredEvent} published with the tool
     * name — so the checker leaves nothing silent; the overdue legacy row is
     * still settled by the checker itself in the same sweep.
     */
    @Test
    void checkExpiredApprovals_settlesAnOverdueNativeAskThroughTheCoordinatorPath() {
        Instant now = Instant.now();
        Map<UUID, Approval> approvalStore = new ConcurrentHashMap<>();
        Map<UUID, AcpPermissionRequest> permissionStore = new ConcurrentHashMap<>();
        lenient().when(approvalRepository.save(any(Approval.class))).thenAnswer(inv -> {
            Approval approval = inv.getArgument(0);
            if (approval.getId() == null) {
                approval.setId(UUID.randomUUID());
            }
            approvalStore.put(approval.getId(), approval);
            return approval;
        });
        lenient().when(approvalRepository.findById(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(approvalStore.get(inv.<UUID>getArgument(0))));
        lenient().when(permissionRepository.save(any(AcpPermissionRequest.class))).thenAnswer(inv -> {
            AcpPermissionRequest row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId(UUID.randomUUID());
            }
            permissionStore.put(row.getId(), row);
            return row;
        });
        lenient().when(permissionRepository.findByRunIdAndSessionIdAndRequestId(
                        any(UUID.class), any(String.class), any(String.class)))
                .thenAnswer(inv -> {
                    UUID runId = inv.getArgument(0);
                    String sessionId = inv.getArgument(1);
                    String requestId = inv.getArgument(2);
                    return permissionStore.values().stream()
                            .filter(row -> runId.equals(row.getRunId()))
                            .filter(row -> sessionId.equals(row.getSessionId()))
                            .filter(row -> requestId.equals(row.getRequestId()))
                            .findFirst();
                });
        lenient().when(permissionRepository.findByDeliveryStateAndExpiresAtBefore(
                        any(String.class), any(Instant.class)))
                .thenAnswer(inv -> {
                    String deliveryState = inv.getArgument(0);
                    Instant cutoff = inv.getArgument(1);
                    return permissionStore.values().stream()
                            .filter(row -> deliveryState.equals(row.getDeliveryState()))
                            .filter(row -> row.getExpiresAt().isBefore(cutoff))
                            .toList();
                });

        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        ApprovalGate gate = new ApprovalGate(approvalRepository, toolCallRepository, eventPublisher, 30_000L);
        PermissionCoordinator coordinator = new PermissionCoordinator(gate, approvalRepository,
                decisionLocks, permissionRepository, new WriteGrantService(permissionRepository, clock),
                new PlatformMcpAutoApproval(new McpProperties()), eventPublisher, clock);
        UUID approvalId = coordinator.register(new NativePermission(UUID.randomUUID(),
                "ses_fixture_1", "0", "run_agent", PermissionTarget.NATIVE_TOOL, "{}",
                List.of(new PermissionOption("cancel", PermissionChoice.DENY),
                        new PermissionOption("proceed_once", PermissionChoice.ALLOW_ONCE)),
                now.minusSeconds(30)));

        Approval legacy = TestDataBuilder.anApproval()
                .withStatus(ApprovalStatus.PENDING)
                .withReason("waiting on operator")
                .withExpiresAt(now.minusSeconds(60))
                .build();
        // The real query is source-agnostic: it returns the legacy row and the
        // still-PENDING native approval alike.
        when(approvalRepository.findByStatusAndExpiresAtBefore(eq(ApprovalStatus.PENDING), any(Instant.class)))
                .thenReturn(List.of(legacy, approvalStore.get(approvalId)));

        new ApprovalExpiryChecker(approvalRepository, gate, coordinator).checkExpiredApprovals();

        // The native ask settled through the coordinator path: the recorded
        // expiry adjudication ...
        Approval nativeApproval = approvalStore.get(approvalId);
        assertThat(nativeApproval.getStatus()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(nativeApproval.getReason()).isEqualTo(PermissionCoordinator.EXPIRY_REASON);
        assertThat(nativeApproval.getDecidedAt()).isNotNull().isAfterOrEqualTo(now);
        // ... the ledger delivery moved to EXPIRED ...
        AcpPermissionRequest nativeRow = permissionStore.values().stream()
                .filter(row -> approvalId.equals(row.getApprovalId()))
                .findFirst().orElseThrow();
        assertThat(nativeRow.getDeliveryState()).isEqualTo(PermissionDeliveryState.EXPIRED.name());
        // ... and the expired event announcing the tool.
        ArgumentCaptor<ApprovalExpiredEvent> event = ArgumentCaptor.forClass(ApprovalExpiredEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().getApprovalId()).isEqualTo(approvalId);
        assertThat(event.getValue().getToolName()).isEqualTo("run_agent");

        // The legacy row keeps the checker's own settle path in the same sweep.
        assertThat(legacy.getStatus()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(legacy.getReason()).isEqualTo("Auto-rejected: approval expired");
    }
}
