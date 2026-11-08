package io.aria.conductor.aria.service;

import io.aria.conductor.aria.repository.AriaRunStateRepository;
import io.aria.conductor.common.model.RunStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

/**
 * Waits for the run an Aria sync chat started (F2/F6).
 *
 * <p>The wait is deliberately transaction-free. Every read runs in its own
 * short {@code REQUIRES_NEW} read-only transaction, and the sleep between reads
 * happens after that transaction has committed — so a minute-long sync window
 * never pins the request's Hikari connection (issue #58: a long sync chat made
 * Hikari log a leaked-then-returned connection). The caller keeps the request
 * thread; only the JDBC connection is released between polls.
 *
 * <p>The outcome is the last observed committed state plus whether the window
 * expired. It never invents a terminal state: a still-live run is reported as
 * exactly the status that was read.
 */
@Slf4j
@Component
public class AriaSyncRunWaiter {

    /** How often the committed state is re-read while the run is live. */
    static final long DEFAULT_POLL_INTERVAL_MS = 500L;
    /** Number of polls before the bounded sync window is declared over. */
    static final int DEFAULT_MAX_POLLS = 120;
    /** Extra wait for a budget-exhaustion summary after the window (2-5s LLM call). */
    static final long DEFAULT_GRACE_MS = 3_000L;

    /** Test seam: the sleep is swappable so a test can observe the transaction flag. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** The committed state observed last, and whether the sync window expired on it. */
    public record Outcome(AriaRunStateRepository.RunSnapshot snapshot, boolean timedOut) {
    }

    private final AriaRunStateRepository states;
    private final TransactionTemplate readTransaction;
    private final Sleeper sleeper;
    private final long pollIntervalMillis;
    private final int maxPolls;
    private final long graceMillis;

    @Autowired
    public AriaSyncRunWaiter(AriaRunStateRepository states, PlatformTransactionManager transactionManager) {
        this(states, readOnlyRequiresNew(transactionManager), Thread::sleep,
                DEFAULT_POLL_INTERVAL_MS, DEFAULT_MAX_POLLS, DEFAULT_GRACE_MS);
    }

    AriaSyncRunWaiter(AriaRunStateRepository states, TransactionTemplate readTransaction,
                      Sleeper sleeper, long pollIntervalMillis, int maxPolls, long graceMillis) {
        this.states = states;
        this.readTransaction = readTransaction;
        this.sleeper = sleeper;
        this.pollIntervalMillis = pollIntervalMillis;
        this.maxPolls = maxPolls;
        this.graceMillis = graceMillis;
    }

    private static TransactionTemplate readOnlyRequiresNew(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(true);
        return template;
    }

    /**
     * Polls the committed run state until it reaches a terminal status or the
     * sync window expires. {@link Thread#interrupt()} ends the wait early and
     * returns the last observed state.
     */
    public Outcome await(UUID runId) {
        for (int polls = 0; polls < maxPolls; polls++) {
            AriaRunStateRepository.RunSnapshot current = readCommittedState(runId);
            if (isTerminal(current)) {
                return new Outcome(current, false);
            }
            if (!sleep(pollIntervalMillis)) {
                return new Outcome(current, true);
            }
        }
        // Bounded grace for the budget-exhaustion summary, still outside any transaction.
        if (!sleep(graceMillis)) {
            return new Outcome(readCommittedState(runId), true);
        }
        AriaRunStateRepository.RunSnapshot finalCheck = readCommittedState(runId);
        if (isTerminal(finalCheck)) {
            log.info("Aria sync chat: run {} reached {} during the grace wait",
                    runId, finalCheck.getStatus());
        } else {
            log.warn("Aria sync chat: run {} did not reach a terminal status within the sync window",
                    runId);
        }
        return new Outcome(finalCheck, true);
    }

    /** One read, in its own short transaction; the caller's connection is not held. */
    private AriaRunStateRepository.RunSnapshot readCommittedState(UUID runId) {
        return readTransaction.execute(status -> states.findSnapshotById(runId).orElse(null));
    }

    private static boolean isTerminal(AriaRunStateRepository.RunSnapshot snapshot) {
        if (snapshot == null || snapshot.getStatus() == null) {
            return false;
        }
        RunStatus status = snapshot.getStatus();
        return status == RunStatus.COMPLETED
                || status == RunStatus.FAILED
                || status == RunStatus.CANCELLED
                || status == RunStatus.ABORTED;
    }

    /** @return false when the thread was interrupted (the wait must end). */
    private boolean sleep(long millis) {
        try {
            sleeper.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
