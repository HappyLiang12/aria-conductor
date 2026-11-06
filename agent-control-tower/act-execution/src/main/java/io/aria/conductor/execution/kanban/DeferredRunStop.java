package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.service.RunService;
import io.aria.conductor.common.exception.ErrorSurface;
import io.aria.conductor.common.model.RunErrorText;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The deferred stop of a linked run: a card move that ends its run commits the card first and
 * the core stop runs afterwards, off the request thread (#111 fix round 2, rulings 5 and 6).
 *
 * <p>Two facts drive this class. The card transaction is already durable when an
 * {@code afterCommit} callback runs, so a refusal must never travel back to the HTTP caller --
 * before this class it did, as a 409 over a card that was already CANCELLED, and because the
 * board refuses to re-run a transition the card already reached, that answer was a dead end with
 * a live core behind it. And {@code RunService.cancelRun} suspends the caller's transaction while
 * its verified stop blocks for up to 30 s, which pins two pooled connections per deferred cancel;
 * the queue here is the operator's bounded escape from that.
 *
 * <p>A refusal is therefore recorded, not swallowed: the run row carries the durable
 * {@link #PENDING_STOP_CODE} intent (visible through the run views, which already surface a run's
 * error identity), it is logged at WARN with the run id and the exact refusal reason, and
 * {@link #retryPendingStops()} re-drives it until the stop is verified or the run ends by itself.
 * The two entry points then differ exactly as far as their caller can still act: {@link #submit}
 * answers with that record alone, because the card move behind it is already committed and the
 * board will not re-run it; {@link #runNow} rethrows the refusal on top of the record, because a
 * caller that committed nothing is the one caller whose response can still carry the refusal.
 */
@Slf4j
@Component
public class DeferredRunStop {

    /** The durable pending-stop intent recorded on a live run whose verified stop was refused. */
    public static final String PENDING_STOP_CODE = "RUN_STOP_PENDING";

    /** The operator surface that can act on a pending stop: the runtime, not a credential. */
    public static final String PENDING_STOP_SURFACE = ErrorSurface.RUNTIME.name();

    /** The bound on queued stops; a full queue degrades to the durable pending record, never a drop. */
    static final int QUEUE_CAPACITY = 100;

    private final RunService runService;
    private final RunRepository runRepository;
    private final Executor stopExecutor;

    /**
     * The production wiring, and the ONE constructor Spring injects: with the test seam below also
     * present, an unannotated pair of constructors leaves the container with no autowire candidate
     * and it falls back to a default constructor this class does not have -- the whole application
     * context then fails to start ("No default constructor found" for deferredRunStop).
     */
    @Autowired
    public DeferredRunStop(RunService runService, RunRepository runRepository) {
        this(runService, runRepository, boundedStopExecutor());
    }

    /** The test seam: a same-thread or controlled executor, so no test waits on a worker thread. */
    DeferredRunStop(RunService runService, RunRepository runRepository, Executor stopExecutor) {
        this.runService = runService;
        this.runRepository = runRepository;
        this.stopExecutor = stopExecutor;
    }

    /**
     * The single worker with a bounded queue: one stop at a time (each may block for the full 30 s
     * verification window), so a burst of card cancels cannot occupy request threads or exhaust the
     * connection pool; the overflow waits in the queue and an overflowed queue is recorded.
     */
    private static Executor boundedStopExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
                    Thread thread = new Thread(runnable, "deferred-run-stop");
                    thread.setDaemon(true);
                    return thread;
                });
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /**
     * Hands the run's stop to the stop worker. Never throws: the caller is an {@code afterCommit}
     * callback whose card move is already committed, and a queue that cannot accept the stop
     * becomes the same durable pending record a refused stop becomes.
     */
    public void submit(UUID runId) {
        try {
            stopExecutor.execute(() -> {
                // The recorded refusal is deliberately not rethrown here: the card move this stop
                // follows is already durable, and the 409 a refusal used to raise over it described
                // a card the board will never move again.
                cancelAndRecordRefusal(runId);
            });
        } catch (RejectedExecutionException full) {
            log.warn("Run {}: the deferred stop could not be queued ({}); recording it as a pending"
                    + " stop for the retry sweep", runId, full.getMessage());
            recordPendingStop(runId, "the stop queue is full (" + full.getMessage() + ")");
        }
    }

    /**
     * Runs the stop on the calling thread. Only for callers with no committed card move to protect
     * (unit callers, non-transactional entry points): such a caller keeps seeing the exact refusal,
     * which is what its response needs.
     *
     * <p>The refusal is recorded for the retry sweep first and then rethrown unchanged, so both
     * promises hold at once: nothing is swallowed, and {@code RunService.cancelRun}'s exact reason
     * stays the #111 guarantee that a live core which did not confirm its stop is never answered as
     * a cancellation. Only the {@code afterCommit} path may answer a refusal with the card move,
     * because there the transition it would have 409'd about is already committed.
     */
    public void runNow(UUID runId) {
        RuntimeException refused = cancelAndRecordRefusal(runId);
        if (refused != null) {
            throw refused;
        }
    }

    /**
     * The verified cancel, with the refusal handled exactly once: WARN with the run id and the
     * exact reason, and the durable pending-stop intent written so the retry sweep re-drives it.
     *
     * @return the refusal the caller must still answer (null when the stop completed), never a
     *         swallowed one
     */
    private RuntimeException cancelAndRecordRefusal(UUID runId) {
        try {
            runService.cancelRun(runId);
            log.info("Run {}: the deferred stop completed", runId);
            return null;
        } catch (RuntimeException refused) {
            // Never swallowed: WARN with the run id and the exact reason, and the durable intent.
            log.warn("Run {}: the deferred stop was refused ({}); the card move stays committed and"
                    + " the stop is recorded as pending until the retry sweep verifies it",
                    runId, refused.getMessage());
            recordPendingStop(runId, refused.getMessage());
            return refused;
        }
    }

    /**
     * Writes the durable pending-stop intent on the run. The conditional write leaves a run that
     * finalized meanwhile untouched, so the sweep can never resurrect it.
     */
    void recordPendingStop(UUID runId, String reason) {
        String message = RunErrorText.bound("Core stop pending: " + reason
                + " -- the board move is committed; the coordinator retries the verified stop"
                + " (aria.runs.stop-retry-delay-ms).");
        try {
            int written = runRepository.recordPendingStopIfCancelable(runId, message, PENDING_STOP_CODE,
                    PENDING_STOP_SURFACE, Instant.now(), RunService.cancelableStatuses());
            if (written == 0) {
                log.warn("Run {}: the pending stop was not recorded because the committed run is"
                        + " terminal or missing; no live core is left to stop", runId);
            }
        } catch (RuntimeException recordFailure) {
            // The refusal reason is already WARNed above; a failed record must not kill the worker.
            log.error("Run {}: recording the pending stop failed ({}); the retry sweep cannot see it"
                    + " -- cancel the run again to re-drive the stop", runId, recordFailure.getMessage(),
                    recordFailure);
        }
    }

    /**
     * The retry sweep for pending stops: every live run still carrying {@link #PENDING_STOP_CODE}
     * gets its verified stop re-driven on the same bounded worker. A run that reached a terminal
     * state is no longer a target (the marker sits on a finished row as history, and the
     * conditional write of the cancel refuses it), so no stop is ever claimed twice.
     */
    @Scheduled(fixedDelayString = "${aria.runs.stop-retry-delay-ms:30000}",
            initialDelayString = "${aria.runs.stop-retry-delay-ms:30000}")
    public void retryPendingStops() {
        java.util.List<UUID> pending;
        try {
            pending = runRepository.findIdsPendingStop(PENDING_STOP_CODE, RunService.cancelableStatuses());
        } catch (RuntimeException readFailure) {
            log.error("Reading the pending-stop intents failed: {}", readFailure.getMessage(), readFailure);
            return;
        }
        for (UUID runId : pending) {
            log.warn("Run {} still carries a pending core stop; re-driving the verified cancel", runId);
            submit(runId);
        }
    }
}
