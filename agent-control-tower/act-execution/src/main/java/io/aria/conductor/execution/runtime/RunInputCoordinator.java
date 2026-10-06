package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.event.RunWaitingForInputEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Map;
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

    public RunInputCoordinator(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /** Publishes {@link RunWaitingForInputEvent} and returns the future the run parks on. */
    public CompletableFuture<OperatorInput> requestInput(UUID runId, String question) {
        CompletableFuture<OperatorInput> future = new CompletableFuture<>();
        pending.put(runId, new PendingInput(future));
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
}
