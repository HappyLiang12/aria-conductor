package io.aria.conductor.execution.approval;

import io.aria.conductor.common.event.RunCompletedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

/**
 * Settles a run's still-PENDING native asks when the run's lifecycle ends.
 *
 * <p>The run-end native settle must not depend on the engine loop's finalize
 * timing: the loop finalize ({@code permissionCoordinator.cancelPendingForRun}
 * beside the gate sweep) does not run promptly when a LIVE, gate-blocked run is
 * cancelled externally — {@code RunService.cancelRun} writes CANCELLED and
 * publishes {@link RunCompletedEvent}, but the finalize can lag well past the
 * event (observed: the run's native ask stays PENDING for 30s+, and forever
 * until the deadline). The run-completed event IS the run-end signal, so
 * settling here makes every settle path prompt.
 *
 * <p>Precedence mirrors the loop finalize: asks whose own recorded window has
 * already passed are adjudicated by that timeout first
 * ({@link PermissionCoordinator#expirePendingForRun}), so the run ending can
 * never rewrite a timed-out permission ask as "run ended"; only still-open asks
 * read as cancelled by the run end ({@link PermissionCoordinator#cancelPendingForRun}).
 *
 * <p>The settle runs in its own {@link TransactionTemplate} with
 * {@code REQUIRES_NEW}, like the sibling after-commit listeners: an after-commit
 * invocation still runs inside the completing transaction's cleanup scope (the
 * entity-manager holder is bound but its transaction is closed), so a plain
 * {@code @Transactional} join would execute the coordinator's bulk update with
 * no live transaction and fail. {@code REQUIRES_NEW} suspends that zombie scope
 * and opens a real one; the catch sits outside the template boundary, so a
 * failure is logged and never rethrown into the event multicaster.
 *
 * <p>Idempotent: the coordinator only rewrites PENDING rows, so the engine
 * loop's own finalize settle — which may still run later — finds nothing left
 * to do.
 */
@Slf4j
@Component
public class RunEndPermissionSettler {

    private final PermissionCoordinator permissionCoordinator;
    private final TransactionTemplate settleTransaction;

    public RunEndPermissionSettler(PermissionCoordinator permissionCoordinator,
                                   PlatformTransactionManager transactionManager) {
        this.permissionCoordinator = permissionCoordinator;
        this.settleTransaction = new TransactionTemplate(transactionManager);
        this.settleTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRunCompleted(RunCompletedEvent event) {
        try {
            settleTransaction.executeWithoutResult(status -> settle(event.getRunId()));
        } catch (Exception e) {
            log.warn("Failed to settle pending native asks for ended run {}: {}",
                    event.getRunId(), e.getMessage());
        }
    }

    private void settle(UUID runId) {
        permissionCoordinator.expirePendingForRun(runId, Instant.now());
        permissionCoordinator.cancelPendingForRun(runId);
    }
}
