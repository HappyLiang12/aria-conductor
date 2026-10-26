package io.aria.conductor.agent.service;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.exception.InvalidStateTransitionException;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * The short transactional writer that records a run's terminal CANCELLED state
 * (#111 fix round 1). Deliberately separate from {@link RunService}: the
 * verified core stop (bounded at 30 s) must run OUTSIDE any database
 * transaction, so {@code RunService.cancelRun} suspends the caller's transaction
 * and calls the runtime first; only then does this writer open one short
 * transaction.
 *
 * <p>The transition is a single conditional UPDATE
 * ({@link RunRepository#cancelIfCancelable}) whose WHERE clause evaluates the
 * row's committed status. That is what makes the terminal race safe: a run that
 * COMPLETED/FAILED while the stop was in flight -- or a concurrent cancel that
 * already committed -- makes this update report 0 rows, so no stale
 * read-then-save can overwrite it. The {@link RunCompletedEvent} is published
 * only by the writer whose update won, which is what keeps the engine's
 * finalize path and this path to exactly one completion event per transition.
 */
@Component
public class RunCancellationWriter {

    private final RunRepository runRepository;
    private final ApplicationEventPublisher eventPublisher;

    public RunCancellationWriter(RunRepository runRepository,
                                 ApplicationEventPublisher eventPublisher) {
        this.runRepository = runRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Atomically moves a cancelable run to CANCELLED and publishes its
     * completion event, or refuses without writing when the run already
     * reached a terminal state (or does not exist).
     *
     * @return the committed run read after this writer won the transition
     * @throws InvalidStateTransitionException when the database refused the
     *         write because the committed state is no longer cancelable
     * @throws ResourceNotFoundException       when the run row does not exist
     */
    @Transactional
    public Run cancel(UUID id) {
        Instant now = Instant.now();
        int updated = runRepository.cancelIfCancelable(id, RunStatus.CANCELLED, now, now,
                RunService.cancelableStatuses());
        if (updated == 0) {
            // The caller's first read may be stale (managed instance or an older
            // snapshot). Report the state that actually stands, in this transaction.
            RunStatus actual = runRepository.findStateById(id)
                    .map(RunRepository.RunStateView::getStatus)
                    .orElseThrow(() -> new ResourceNotFoundException("Run", id));
            throw new InvalidStateTransitionException("Run", actual.name(), RunStatus.CANCELLED.name());
        }
        Run run = runRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Run", id));
        eventPublisher.publishEvent(new RunCompletedEvent(this, run.getId(), run.getAgentId(),
                RunStatus.CANCELLED));
        return run;
    }
}
