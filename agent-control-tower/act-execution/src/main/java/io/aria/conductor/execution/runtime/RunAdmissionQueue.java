package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.execution.adk.TaskExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * In-process admission queue for run-owned cores: at most
 * {@code aria.runs.max-active} non-Aria runs and {@code aria.runs.aria-reserved}
 * Aria runs execute at once; the rest park here as PENDING (their mirror cards
 * wait in TODO) and start FIFO when a slot frees. Slots release on every
 * terminal transition (the component listens to RunCompletedEvent), and a run
 * cancelled while queued is dequeued with a truthful ABORTED refusal.
 */
@Slf4j
@Component
public class RunAdmissionQueue {

    private record Waiter(UUID runId, UUID agentId, Instant createdAt) {
        boolean aria() {
            return AriaConstants.ARIA_AGENT_ID.equals(agentId);
        }
    }

    private final RunAdmissionProperties properties;
    private final Object monitor = new Object();
    /** Waiting runs, per pool, FIFO by (createdAt, runId). */
    private final Deque<Waiter> workersWaiting = new ArrayDeque<>();
    private final Deque<Waiter> ariaWaiting = new ArrayDeque<>();
    /** Runs currently holding a permit: runId -> isAria. */
    private final Map<UUID, Boolean> active = new HashMap<>();

    public RunAdmissionQueue(RunAdmissionProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /**
     * Blocks until this run holds an admission permit. Waits as PENDING; throws
     * {@link TaskExecutionException.Cause#ABORTED} when the run left the queue
     * (cancelled) before a slot was free.
     */
    public void acquire(UUID runId, UUID agentId, Instant createdAt) {
        Objects.requireNonNull(runId, "runId");
        Waiter waiter = new Waiter(runId, agentId, createdAt != null ? createdAt : Instant.now());
        synchronized (monitor) {
            insertSorted(waiter);
            promote();
            try {
                while (!active.containsKey(runId)) {
                    if (!isWaiting(runId)) {
                        throw aborted(runId);
                    }
                    monitor.wait(1000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // The thread is walking away: drop its waiter and any permit a
                // racing grant already handed it, or a dead thread holds a slot.
                removeAndPromote(runId);
                throw new TaskExecutionException(TaskExecutionException.Cause.ABORTED,
                        "Run " + runId + " was interrupted while waiting for an admission slot", e);
            }
        }
    }

    /** Idempotent: releases the permit of a terminal run and admits the next waiters. */
    public void release(UUID runId) {
        synchronized (monitor) {
            active.remove(runId);
            promote();
            monitor.notifyAll();
        }
    }

    /**
     * Settles a run that reached a terminal state: atomically drops any permit it
     * holds and removes it from both wait deques, then admits the next waiters.
     * {@code release()} + {@code dequeue()} in sequence is NOT equivalent — a
     * grant landing between the two would admit an already-terminal run — so the
     * terminal-event listener and the engine's post-admission revalidation use
     * this instead. Idempotent.
     */
    public void settle(UUID runId) {
        synchronized (monitor) {
            removeAndPromote(runId);
        }
    }

    /** Removes a run that is still waiting; its acquire() throws ABORTED. */
    public void dequeue(UUID runId) {
        synchronized (monitor) {
            workersWaiting.removeIf(w -> w.runId().equals(runId));
            ariaWaiting.removeIf(w -> w.runId().equals(runId));
            monitor.notifyAll();
        }
    }

    /* Test seams (package-private). */

    int activeWorkerCount() {
        synchronized (monitor) {
            return (int) active.values().stream().filter(aria -> !aria).count();
        }
    }

    int activeAriaCount() {
        synchronized (monitor) {
            return (int) active.values().stream().filter(aria -> aria).count();
        }
    }

    boolean isWaiting(UUID runId) {
        synchronized (monitor) {
            return workersWaiting.stream().anyMatch(w -> w.runId().equals(runId))
                    || ariaWaiting.stream().anyMatch(w -> w.runId().equals(runId));
        }
    }

    Set<UUID> activeRunIds() {
        synchronized (monitor) {
            return new HashSet<>(active.keySet());
        }
    }

    /** Removes a run from both wait deques and the active map, then promotes (must hold the monitor). */
    private void removeAndPromote(UUID runId) {
        workersWaiting.removeIf(w -> w.runId().equals(runId));
        ariaWaiting.removeIf(w -> w.runId().equals(runId));
        active.remove(runId);
        promote();
        monitor.notifyAll();
    }

    /** Admits head waiters while their pool has capacity (called under monitor). */
    private void promote() {
        grantHead(workersWaiting, properties.getMaxActive(), false);
        grantHead(ariaWaiting, properties.getAriaReserved(), true);
    }

    private void grantHead(Deque<Waiter> waiting, int capacity, boolean aria) {
        // 0 = unlimited: the whole pool drains immediately.
        while (!waiting.isEmpty() && (capacity == 0 || (aria ? activeAriaCount() : activeWorkerCount()) < capacity)) {
            Waiter granted = waiting.poll();
            active.put(granted.runId(), aria);
            log.info("Admitted run {} ({}) to the {} pool", granted.runId(),
                    granted.aria() ? "aria" : "worker", granted.aria() ? "aria" : "worker");
        }
    }

    private void insertSorted(Waiter waiter) {
        Deque<Waiter> queue = waiter.aria() ? ariaWaiting : workersWaiting;
        List<Waiter> sorted = new ArrayList<>(queue);
        sorted.add(waiter);
        sorted.sort(Comparator.comparing(Waiter::createdAt).thenComparing(w -> w.runId().toString()));
        queue.clear();
        queue.addAll(sorted);
    }

    private static TaskExecutionException aborted(UUID runId) {
        return new TaskExecutionException(TaskExecutionException.Cause.ABORTED,
                "Run " + runId + " left the admission queue before a slot was free");
    }
}
