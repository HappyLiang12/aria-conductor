package io.aria.conductor.execution.approval;

import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.model.RunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunEndPermissionSettlerTest {

    private PermissionCoordinator permissionCoordinator;
    private RunEndPermissionSettler settler;

    @BeforeEach
    void setUp() {
        permissionCoordinator = mock(PermissionCoordinator.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        settler = new RunEndPermissionSettler(permissionCoordinator, transactionManager);
    }

    @Test
    void onRunCompleted_shouldSettlePendingAsksForTheRun() {
        UUID runId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        RunCompletedEvent event = new RunCompletedEvent(this, runId, agentId, RunStatus.CANCELLED);

        settler.onRunCompleted(event);

        // Own-window expiries first (a timed-out ask is never rewritten as
        // "run ended"), then the remainder as cancelled by the run end.
        InOrder order = inOrder(permissionCoordinator);
        order.verify(permissionCoordinator).expirePendingForRun(eq(runId), any(Instant.class));
        order.verify(permissionCoordinator).cancelPendingForRun(runId);
    }

    @Test
    void onRunCompleted_shouldNotPropagateSettleFailure() {
        UUID runId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        RunCompletedEvent event = new RunCompletedEvent(this, runId, agentId, RunStatus.CANCELLED);
        doThrow(new RuntimeException("coordinator failure")).when(permissionCoordinator).cancelPendingForRun(runId);

        // Should not throw: the settle failure is logged and swallowed.
        settler.onRunCompleted(event);

        verify(permissionCoordinator).cancelPendingForRun(runId);
    }
}
