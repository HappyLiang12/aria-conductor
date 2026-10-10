package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.event.RunWaitingForInputEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Parks one waiting run per process (2026-10-05 spec §2/§5): the coordinated
 * run thread parks on the future, the operator's answer or finalize signal
 * completes it. No timeout by design (operator decision D3 — infinite wait);
 * the run's admission slot stays held for the same reason. A re-request for a
 * run that is somehow already parked replaces the parked future — the loop
 * never stacks two waiters for one run, and the superseded future simply
 * never completes.
 */
@Service
public class RunInputCoordinator {

    /** What woke the parked run: the operator's answer, or a finalize signal. */
    public record OperatorInput(String answer, boolean finalizeRequested) {
    }

    private record PendingInput(CompletableFuture<OperatorInput> future) {
    }

    private final ApplicationEventPublisher eventPublisher;
    private final Map<UUID, PendingInput> pending = new ConcurrentHashMap<>();
    /**
     * Sticky termination intent (Plan B task 6): a cancel landing between the
     * loop's turn result and the park call finds no pending entry, so
     * {@link #requestFinalize} would return false and the run would park
     * forever. The engine's CANCELLED listener (and the verified-stop hook)
     * records the intent here instead; the loop's imminent
     * {@link #requestInput} consumes it and returns the finalize signal
     * immediately, without publishing a waiting event for a run that is
     * already terminal in the database.
     */
    private final Set<UUID> terminationIntent = ConcurrentHashMap.newKeySet();

    public RunInputCoordinator(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /** Publishes {@link RunWaitingForInputEvent} and returns the future the run parks on. */
    public CompletableFuture<OperatorInput> requestInput(UUID runId, String question) {
        // A termination intent recorded while nobody was parked is consumed here:
        // the run is already terminal, so it gets the finalize signal at once and
        // no waiting event is published.
        if (terminationIntent.remove(runId)) {
            return CompletableFuture.completedFuture(new OperatorInput(null, true));
        }
        CompletableFuture<OperatorInput> future = new CompletableFuture<>();
        PendingInput entry = new PendingInput(future);
        pending.put(runId, entry);
        // Interleaving closure (park-vs-intent): an intent can land between the
        // fast-path check above and the put. Re-checking here catches it; an
        // intent landing after this re-check finds our entry via
        // recordTerminationIntent's atomic remove-and-get and wakes us instead
        // of adding an intent. Together every ordering ends with the run woken,
        // never parked forever. Should the conditional remove lose our entry to
        // a concurrent submitAnswer/requestFinalize/recordTerminationIntent, the
        // taker completes our future, so falling through to the park is safe.
        if (terminationIntent.remove(runId) && pending.remove(runId, entry)) {
            return CompletableFuture.completedFuture(new OperatorInput(null, true));
        }
        eventPublisher.publishEvent(new RunWaitingForInputEvent(this, runId, question));
        return future;
    }

    /** Wakes the parked run with the operator's answer; refused when not waiting. */
    public void submitAnswer(UUID runId, String answer) {
        PendingInput waiting = pending.get(runId);
        if (waiting == null) {
            throw new IllegalStateException("Run " + runId + " is not waiting for operator input");
        }
        waiting.future().complete(new OperatorInput(answer, false));
        pending.remove(runId, waiting);
    }

    /** Wakes the parked run with the finalize signal; false when the run is not waiting. */
    public boolean requestFinalize(UUID runId) {
        PendingInput waiting = pending.get(runId);
        if (waiting == null) {
            return false;
        }
        waiting.future().complete(new OperatorInput(null, true));
        pending.remove(runId, waiting);
        return true;
    }

    /**
     * Records a termination intent for the run (Plan B task 6 ruling 1): a
     * parked run is woken exactly like {@link #requestFinalize}; a run that is
     * not (yet) parked gets a sticky intent, so the park call the loop is about
     * to make returns the finalize signal instead of waiting forever. The
     * remove-and-get is atomic, so an entry put concurrently by
     * {@link #requestInput} cannot be missed in favor of a stranded intent.
     */
    public void recordTerminationIntent(UUID runId) {
        PendingInput waiting = pending.remove(runId);
        if (waiting != null) {
            waiting.future().complete(new OperatorInput(null, true));
            return;
        }
        terminationIntent.add(runId);
    }
}
