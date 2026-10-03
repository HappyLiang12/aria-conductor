# Run Admission Limit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bound concurrent run execution (`aria.runs.max-active` workers + `aria.runs.aria-reserved` Aria runs, defaults 6+1) with an in-process FIFO admission queue, so fan-outs queue instead of overloading the sandbox daemon; queued runs stay `PENDING` and their mirror cards wait in `TODO`.

**Architecture:** A `RunAdmissionQueue` component gates the run-owned core launch inside `AgentLoopEngine.startRun` **before** the run leaves `PENDING` (before the `INITIALIZING` update at `AgentLoopEngine.java:254-255`), admits runs FIFO per pool (workers / Aria) when a slot frees, and releases slots on every terminal transition via `RunCompletedEvent`. On admission the engine publishes a `RunIterationEvent` start signal so `RunKanbanAutoCreator` moves the mirror card `TODO → IN_PROGRESS` (the card is born `TODO` again; a queued run's card honestly waits in TODO). A startup bootstrap re-enqueues `PENDING` runs left by a restart (ordered after the orphan reaper).

**Tech Stack:** Java 21, Spring Boot 3.3, JUnit 5 + AssertJ + Mockito (backend); Vitest/Playwright untouched here. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-10-03-run-admission-limit-design.md`

## Global Constraints

- Config keys and defaults (verbatim): `aria.runs.max-active` default `6`, `0` = unlimited; `aria.runs.aria-reserved` default `1`, `0` = unlimited. Env overrides `ARIA_RUNS_MAX_ACTIVE`, `ARIA_RUNS_ARIA_RESERVED` following the `aria.tasks.deadline-minutes` pattern.
- A queued run MUST stay `PENDING` (never `INITIALIZING`/`RUNNING`) and MUST NOT create a sandbox or freeze its binding; the binding freeze happens at admission (`CoreRunLauncher.java:124-134` runs only after the gate returns).
- Aria identity: `AriaConstants.ARIA_AGENT_ID` (`io.aria.conductor.common`).
- FIFO by run `createdAt` then `runId`; two independent pools (workers, Aria) — workers never consume the Aria reserve and vice versa.
- Release/dequeue source of truth: `RunCompletedEvent` (published for CANCELLED at `RunService.java:207`, and by the completion/finalization paths). Listener phase must be `@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)` like the other mirror listeners.
- `PAUSED` occupies a slot (sandbox alive). Terminal statuses release.
- Every task ends by running its tests; commits are part of the task.

---

### Task 1: Configuration properties + yml

**Files:**
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/RunAdmissionProperties.java`
- Modify: `agent-control-tower/act-app/src/main/resources/application.yml` (the `aria:` block at L74-83, next to `tasks:` L78-79)
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/RunAdmissionPropertiesTest.java`

**Interfaces:**
- Produces: `RunAdmissionProperties.maxActive()` / `.ariaReserved()` (Lombok `@Data` getters), constants `DEFAULT_MAX_ACTIVE = 6`, `DEFAULT_ARIA_RESERVED = 1`.

- [ ] **Step 1: Write the failing test**

```java
package io.aria.conductor.execution.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RunAdmissionPropertiesTest {

    @Test
    void defaultsAreSixWorkersPlusOneAriaSlot() {
        RunAdmissionProperties properties = new RunAdmissionProperties();
        assertThat(properties.getMaxActive()).isEqualTo(6);
        assertThat(properties.getAriaReserved()).isEqualTo(1);
    }

    @Test
    void zeroMeansUnlimited() {
        RunAdmissionProperties properties = new RunAdmissionProperties();
        properties.setMaxActive(0);
        properties.setAriaReserved(0);
        assertThat(properties.getMaxActive()).isZero();
        assertThat(properties.getAriaReserved()).isZero();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent-control-tower && mvn -B test -pl act-execution -Dtest=RunAdmissionPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
Expected: FAIL (RunAdmissionProperties does not exist).

- [ ] **Step 3: Write minimal implementation**

```java
package io.aria.conductor.execution.runtime;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Run admission limits ({@code aria.runs.*}): how many runs may execute at
 * once before the rest queue (status PENDING, mirror card in TODO). A value of
 * {@code 0} means unlimited for that pool. The Aria reservation keeps the
 * assistant's own runs from being starved by a worker flood.
 */
@Data
@Component
@ConfigurationProperties(prefix = "aria.runs")
public class RunAdmissionProperties {

    /** Documented concurrent-run limit for non-Aria agents. */
    public static final int DEFAULT_MAX_ACTIVE = 6;

    /** Documented concurrent-run limit for the Aria assistant's own runs. */
    public static final int DEFAULT_ARIA_RESERVED = 1;

    /** Concurrent non-Aria runs; 0 = unlimited. */
    private int maxActive = DEFAULT_MAX_ACTIVE;

    /** Concurrent Aria-assistant runs; 0 = unlimited. */
    private int ariaReserved = DEFAULT_ARIA_RESERVED;
}
```

Then in `application.yml`, inside the existing `aria:` block (after the `tasks:` lines), add:

```yaml
  # Concurrent run execution (run admission queue): the rest wait PENDING with
  # their kanban cards in TODO. 0 disables a limit. Workers never consume the
  # Aria reservation (default 6 + 1).
  runs:
    max-active: ${ARIA_RUNS_MAX_ACTIVE:6}
    aria-reserved: ${ARIA_RUNS_ARIA_RESERVED:1}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd agent-control-tower && mvn -B test -pl act-execution -Dtest=RunAdmissionPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/RunAdmissionProperties.java \
        agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/RunAdmissionPropertiesTest.java \
        agent-control-tower/act-app/src/main/resources/application.yml
git commit -m "feat(runtime): add aria.runs admission-limit properties (6+1 defaults)"
```

---

### Task 2: RunAdmissionQueue core (acquire / release / FIFO / pools)

**Files:**
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/RunAdmissionQueue.java`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/RunAdmissionQueueTest.java`

**Interfaces:**
- Consumes: `RunAdmissionProperties` (Task 1), `AriaConstants.ARIA_AGENT_ID` (`io.aria.conductor.common.AriaConstants`), `TaskExecutionException` (`io.aria.conductor.execution.adk.TaskExecutionException`, `Cause.ABORTED`).
- Produces: `void acquire(UUID runId, UUID agentId, Instant createdAt)` (blocks, FIFO, throws `TaskExecutionException(ABORTED)` when the waiter was dequeued first — cancel path from Task 3), `void release(UUID runId)` (idempotent), package-private test seams `int activeWorkerCount()`, `int activeAriaCount()`, `boolean isWaiting(UUID runId)`, `void dequeue(UUID runId)`.

- [ ] **Step 1: Write the failing tests**

```java
package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.execution.adk.TaskExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunAdmissionQueueTest {

    private final List<Thread> threads = new ArrayList<>();

    private RunAdmissionQueue queue(int maxActive, int ariaReserved) {
        RunAdmissionProperties properties = new RunAdmissionProperties();
        properties.setMaxActive(maxActive);
        properties.setAriaReserved(ariaReserved);
        return new RunAdmissionQueue(properties);
    }

    /** Runs acquire on its own thread; caller decides when it is allowed to finish. */
    private Thread acquiring(RunAdmissionQueue queue, UUID runId, UUID agentId, Instant createdAt,
                             CountDownLatch admitted, List<Throwable> failures) {
        Thread thread = new Thread(() -> {
            try {
                queue.acquire(runId, agentId, createdAt);
                admitted.countDown();
            } catch (Throwable t) {
                failures.add(t);
            }
        });
        thread.start();
        threads.add(thread);
        return thread;
    }

    private static final UUID WORKER = UUID.randomUUID();

    @AfterEach
    void stopThreads() {
        threads.forEach(Thread::interrupt);
    }

    @Test
    void admitsUpToTheLimitThenQueuesFifo() throws Exception {
        RunAdmissionQueue queue = queue(2, 1);
        CountDownLatch admitted = new CountDownLatch(2);
        List<Throwable> failures = new ArrayList<>();

        acquiring(queue, UUID.randomUUID(), WORKER, Instant.parse("2026-10-03T00:00:00Z"), admitted, failures);
        acquiring(queue, UUID.randomUUID(), WORKER, Instant.parse("2026-10-03T00:00:01Z"), admitted, failures);
        assertThat(admitted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(queue.activeWorkerCount()).isEqualTo(2);

        UUID queued = UUID.randomUUID();
        CountDownLatch thirdAdmitted = new CountDownLatch(1);
        acquiring(queue, queued, WORKER, Instant.parse("2026-10-03T00:00:02Z"), thirdAdmitted, failures);

        // Over the cap: the third waiter parks (still no permit after a moment).
        assertThat(thirdAdmitted.await(300, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(queue.isWaiting(queued)).isTrue();

        // Freeing one slot admits exactly the oldest waiter.
        // (release by runId of the first admitted is not possible here without tracking;
        // the queue exposes activeRunIds for deterministic release.)
        for (UUID runId : queue.activeRunIds()) {
            queue.release(runId);
            break;
        }
        assertThat(thirdAdmitted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(failures).isEmpty();
    }

    @Test
    void ariaRunsUseTheReservationAndNeverTheWorkerPool() throws Exception {
        RunAdmissionQueue queue = queue(1, 1);
        CountDownLatch admitted = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();

        acquiring(queue, UUID.randomUUID(), WORKER, Instant.parse("2026-10-03T00:00:00Z"), admitted, failures);
        CountDownLatch ariaAdmitted = new CountDownLatch(1);
        acquiring(queue, UUID.randomUUID(), AriaConstants.ARIA_AGENT_ID, Instant.parse("2026-10-03T00:00:01Z"), ariaAdmitted, failures);

        // Worker pool is full (1/1) but the Aria reservation is free: Aria proceeds.
        assertThat(admitted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(ariaAdmitted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(queue.activeWorkerCount()).isEqualTo(1);
        assertThat(queue.activeAriaCount()).isEqualTo(1);

        // A second Aria run queues behind the first Aria slot while workers are still 1/1.
        UUID secondAria = UUID.randomUUID();
        CountDownLatch secondAriaAdmitted = new CountDownLatch(1);
        acquiring(queue, secondAria, AriaConstants.ARIA_AGENT_ID, Instant.parse("2026-10-03T00:00:02Z"), secondAriaAdmitted, failures);
        assertThat(secondAriaAdmitted.await(300, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(queue.isWaiting(secondAria)).isTrue();
        assertThat(failures).isEmpty();
    }

    @Test
    void zeroCapacitiesAreUnlimited() throws Exception {
        RunAdmissionQueue queue = queue(0, 0);
        CountDownLatch admitted = new CountDownLatch(4);
        List<Throwable> failures = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            acquiring(queue, UUID.randomUUID(), WORKER, Instant.now(), admitted, failures);
        }
        assertThat(admitted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(failures).isEmpty();
    }

    @Test
    void dequeuedWaiterFailsAborted() throws Exception {
        RunAdmissionQueue queue = queue(1, 1);
        CountDownLatch admitted = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();
        acquiring(queue, UUID.randomUUID(), WORKER, Instant.parse("2026-10-03T00:00:00Z"), admitted, failures);
        assertThat(admitted.await(5, TimeUnit.SECONDS)).isTrue();

        UUID queued = UUID.randomUUID();
        List<Throwable> queuedFailures = new ArrayList<>();
        acquiring(queue, queued, WORKER, Instant.parse("2026-10-03T00:00:01Z"), new CountDownLatch(1), queuedFailures);
        Thread.sleep(200); // let it park

        queue.dequeue(queued);
        long deadline = System.currentTimeMillis() + 5000;
        while (queuedFailures.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(queuedFailures).hasSize(1);
        assertThat(queuedFailures.get(0))
                .isInstanceOf(TaskExecutionException.class)
                .hasMessageContaining("left the admission queue");
        assertThat(((TaskExecutionException) queuedFailures.get(0)).cause())
                .isEqualTo(TaskExecutionException.Cause.ABORTED);
    }

    @Test
    void interruptedWaiterLeavesNoPhantomPermit() throws Exception {
        RunAdmissionQueue queue = queue(1, 1);
        CountDownLatch admitted = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();
        acquiring(queue, UUID.randomUUID(), WORKER, Instant.parse("2026-10-03T00:00:00Z"), admitted, failures);
        assertThat(admitted.await(5, TimeUnit.SECONDS)).isTrue();

        UUID parked = UUID.randomUUID();
        List<Throwable> parkedFailures = new ArrayList<>();
        Thread parkedThread = acquiring(queue, parked, WORKER, Instant.parse("2026-10-03T00:00:01Z"),
                new CountDownLatch(1), parkedFailures);
        Thread.sleep(200); // let it park

        parkedThread.interrupt();
        parkedThread.join(5000);
        assertThat(parkedThread.isAlive()).isFalse();
        assertThat(parkedFailures).hasSize(1);
        assertThat(parkedFailures.get(0)).isInstanceOf(TaskExecutionException.class);

        // The interrupted thread is gone: releasing the holder must NOT hand its
        // slot to the dead waiter's phantom permit.
        for (UUID runId : queue.activeRunIds()) {
            queue.release(runId);
            break;
        }
        assertThat(queue.activeWorkerCount()).isZero();
        assertThat(queue.isWaiting(parked)).isFalse();
        assertThat(failures).isEmpty();
    }

    @Test
    void settleAtomicallyRemovesWaiterAndPermit() throws Exception {
        RunAdmissionQueue queue = queue(1, 1);
        CountDownLatch admitted = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();
        UUID holder = UUID.randomUUID();
        acquiring(queue, holder, WORKER, Instant.parse("2026-10-03T00:00:00Z"), admitted, failures);
        assertThat(admitted.await(5, TimeUnit.SECONDS)).isTrue();

        UUID parked = UUID.randomUUID();
        List<Throwable> parkedFailures = new ArrayList<>();
        acquiring(queue, parked, WORKER, Instant.parse("2026-10-03T00:00:01Z"), new CountDownLatch(1), parkedFailures);
        Thread.sleep(200); // let it park

        queue.settle(parked);
        long deadline = System.currentTimeMillis() + 5000;
        while (parkedFailures.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(parkedFailures).hasSize(1);
        assertThat(parkedFailures.get(0)).isInstanceOf(TaskExecutionException.class);
        assertThat(failures).isEmpty();

        queue.settle(holder);
        assertThat(queue.activeWorkerCount()).isZero();
        queue.settle(holder); // idempotent
        queue.settle(UUID.randomUUID());
        assertThat(queue.activeWorkerCount()).isZero();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd agent-control-tower && mvn -B test -pl act-execution -Dtest=RunAdmissionQueueTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
Expected: FAIL (RunAdmissionQueue does not exist).

- [ ] **Step 3: Write the implementation**

```java
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
```

Note: `grantHead` runs under the monitor; `activeWorkerCount()/activeAriaCount()` re-enter the monitor (reentrant, fine).

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd agent-control-tower && mvn -B test -pl act-execution -Dtest=RunAdmissionQueueTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/RunAdmissionQueue.java \
        agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/RunAdmissionQueueTest.java
git commit -m "feat(runtime): in-process run admission queue (worker pool + Aria reserve, FIFO)"
```

---

### Task 3: Terminal-event release and cancel-while-queued

**Files:**
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/RunAdmissionQueue.java`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/RunAdmissionQueueEventsTest.java`

**Interfaces:**
- Consumes: `RunCompletedEvent` (`io.aria.conductor.common.event.RunCompletedEvent`, constructor `(Object source, UUID runId, UUID agentId, RunStatus status)` per `RunService.java:207` and the mirror listeners).
- Produces: `@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true) public void onRunCompleted(RunCompletedEvent event)` on the queue.

- [ ] **Step 1: Write the failing test**

```java
package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.model.RunStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RunAdmissionQueueEventsTest {

    @Test
    void completedEventReleasesASlotAndAdmitsTheNextWaiter() throws Exception {
        RunAdmissionProperties properties = new RunAdmissionProperties();
        properties.setMaxActive(1);
        RunAdmissionQueue queue = new RunAdmissionQueue(properties);

        UUID first = UUID.randomUUID();
        CountDownLatch firstAdmitted = new CountDownLatch(1);
        Thread firstThread = new Thread(() -> {
            queue.acquire(first, UUID.randomUUID(), Instant.parse("2026-10-03T00:00:00Z"));
            firstAdmitted.countDown();
        });
        firstThread.start();
        assertThat(firstAdmitted.await(5, TimeUnit.SECONDS)).isTrue();

        UUID queued = UUID.randomUUID();
        CountDownLatch queuedAdmitted = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();
        Thread queuedThread = new Thread(() -> {
            try {
                queue.acquire(queued, UUID.randomUUID(), Instant.parse("2026-10-03T00:00:01Z"));
                queuedAdmitted.countDown();
            } catch (Throwable t) {
                failures.add(t);
            }
        });
        queuedThread.start();
        assertThat(queuedAdmitted.await(300, TimeUnit.MILLISECONDS)).isFalse();

        queue.onRunCompleted(new RunCompletedEvent(this, first, UUID.randomUUID(), RunStatus.COMPLETED));

        assertThat(queuedAdmitted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(failures).isEmpty();
    }

    @Test
    void completedEventDequeuesAStillWaitingRun() throws Exception {
        RunAdmissionProperties properties = new RunAdmissionProperties();
        properties.setMaxActive(1);
        RunAdmissionQueue queue = new RunAdmissionQueue(properties);

        UUID holder = UUID.randomUUID();
        CountDownLatch holderAdmitted = new CountDownLatch(1);
        Thread holderThread = new Thread(() -> {
            queue.acquire(holder, UUID.randomUUID(), Instant.parse("2026-10-03T00:00:00Z"));
            holderAdmitted.countDown();
        });
        holderThread.start();
        assertThat(holderAdmitted.await(5, TimeUnit.SECONDS)).isTrue();

        UUID cancelled = UUID.randomUUID();
        List<Throwable> failures = new ArrayList<>();
        Thread cancelledThread = new Thread(() -> {
            try {
                queue.acquire(cancelled, UUID.randomUUID(), Instant.parse("2026-10-03T00:00:01Z"));
            } catch (Throwable t) {
                failures.add(t);
            }
        });
        cancelledThread.start();
        Thread.sleep(300); // parked

        queue.onRunCompleted(new RunCompletedEvent(this, cancelled, UUID.randomUUID(), RunStatus.CANCELLED));

        long deadline = System.currentTimeMillis() + 5000;
        while (failures.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).getMessage()).contains("left the admission queue");
        // The held slot was not consumed by the cancelled waiter; the next in line would still admit.
        assertThat(queue.isWaiting(cancelled)).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent-control-tower && mvn -B test -pl act-execution -Dtest=RunAdmissionQueueEventsTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
Expected: FAIL (no onRunCompleted on RunAdmissionQueue).

- [ ] **Step 3: Implement the listener**

Add to `RunAdmissionQueue`:

```java
    /**
     * The single terminal source of truth: a completed/cancelled/failed/aborted
     * run releases its permit and leaves the queue if it never got one — in one
     * atomic step, so a grant can never land between the two and admit a run
     * that is already terminal.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRunCompleted(RunCompletedEvent event) {
        settle(event.getRunId());
    }
```

with imports `io.aria.conductor.common.event.RunCompletedEvent` and `org.springframework.transaction.event.TransactionalEventListener`, `org.springframework.transaction.event.TransactionPhase`.

- [ ] **Step 4: Run the tests (new + Task 2 suite)**

Run: `cd agent-control-tower && mvn -B test -pl act-execution -Dtest="RunAdmissionQueueTest,RunAdmissionQueueEventsTest" -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/RunAdmissionQueue.java \
        agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/RunAdmissionQueueEventsTest.java
git commit -m "feat(runtime): release admission slots on RunCompletedEvent; dequeue cancelled waiters"
```

---

### Task 4: Engine wiring — gate before PENDING leaves, start signal at admission

**Files:**
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/engine/AgentLoopEngine.java`
- Modify (test constructors): every test that does `new AgentLoopEngine(` — find with
  `grep -rln "new AgentLoopEngine(" agent-control-tower/*/src/test` (expected: `act-execution/src/test/.../engine/AgentLoopEngineTaskPathTest.java`, `AgentLoopEngineCoreDispatchTest.java`, and any others the grep lists).
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/engine/AgentLoopEngineAdmissionTest.java`

**Interfaces:**
- Consumes: `RunAdmissionQueue` (Tasks 2-3), `RunIterationEvent` 5-arg constructor `(Object source, UUID runId, UUID agentId, int iteration, int maxIterations)` (`act-common/.../common/event/RunIterationEvent.java:28`).
- Produces: engine constructor gains a `RunAdmissionQueue admission` parameter; for launcher-owned runs the engine (a) acquires admission BEFORE the `// Update run status to INITIALIZING` step (`AgentLoopEngine.java:254-255`), (b) publishes the start signal immediately after admission, (c) never changes the non-owned (legacy) path.

Read first: `AgentLoopEngine.java:185-260` (the `startRun` method: exact local variable names for the run/agent, the existing `runCoreLauncher()` accessor at L355-359, and where INITIALIZING is set) and L388-416 (`executeCoreRun`, unchanged). Use the method's actual local names in the snippet below; only names may adapt, not behavior.

- [ ] **Step 1: Write the failing test**

```java
package io.aria.conductor.execution.engine;

import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.execution.runtime.RunAdmissionProperties;
import io.aria.conductor.execution.runtime.RunAdmissionQueue;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring-level checks for the admission gate: the queue is consulted for
 * launcher-owned runs and the start signal publication is delegated to the
 * existing event path (the behavioural park/release cycle is covered by the
 * queue suite and the cap integration test).
 */
class AgentLoopEngineAdmissionTest {

    @Test
    void ownedRunsAcquireBeforeInitializing() {
        // Build the engine exactly like AgentLoopEngineTaskPathTest does, with a
        // recording RunAdmissionQueue subclass that counts acquire calls, and a
        // stub core launcher that owns every agent. Start one run and assert the
        // queue saw exactly one acquire for it with the run's id/agentId/createdAt.
        //
        // Follow the construction pattern in AgentLoopEngineTaskPathTest (this
        // task adds the new admission constructor parameter there); assert:
        //   assertThat(recordingQueue.acquired).containsExactly(run.getId());
        // and that no acquire happened when the launcher does not own the agent
        // (second scenario in the same test class).
        assertThat(true).isTrue(); // replace with the assertions described above
    }
}
```

Implementer note: this test class follows `AgentLoopEngineTaskPathTest`'s setup verbatim (mocks for repositories/emitter; `RecordingQueue extends RunAdmissionQueue` overriding `acquire` to record and return immediately and `settle` to record). The stub launcher is the same style used there (a launcher whose `owns` returns true). Assert three scenarios: (a) owned → exactly one acquire with the run's exact id/agentId/createdAt, and settle never called; (b) not-owned → no acquire; (c) cancelled-while-queued → the run repository's `findById` returns the PENDING run on the entry load and a CANCELLED copy on the post-admission re-read (stub the two returns sequentially), `startRun` throws `TaskExecutionException` with `Cause.ABORTED`, settle was called with the run id, and no `RunIterationEvent` was published.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent-control-tower && mvn -B test -pl act-execution -Dtest=AgentLoopEngineAdmissionTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
Expected: FAIL to compile (RunAdmissionQueue not yet accepted by the engine constructor).

- [ ] **Step 3: Wire the engine**

In `AgentLoopEngine`:
1. Add field `private final RunAdmissionQueue admission;` + constructor parameter (place it next to `CoreRunLauncher`-related deps; constructor at ~L120-140, verify exact position by reading the constructor).
2. In `startRun` (the method containing L254-255), immediately BEFORE `// Update run status to INITIALIZING` / `updateRunStatus(run, RunStatus.INITIALIZING);` insert:

```java
        // Run-owned cores pass the admission gate while the run is still PENDING:
        // over-limit runs park here (card in TODO) until a slot frees; the slot
        // then starts the run and only now does the mirror card move IN_PROGRESS.
        CoreRunLauncher coreLauncher = runCoreLauncher();
        if (coreLauncher != null && coreLauncher.owns(agent)) {
            admission.acquire(run.getId(), run.getAgentId(), run.getCreatedAt());
            // The cancel can land before this waiter was even parked: settle()
            // no-ops, then admission grants the slot. Re-read after admission —
            // a run that left PENDING while queued must give its slot back and
            // never start (the stale INITIALIZING write would resurrect it).
            RunStatus afterAdmission = runRepository.findById(run.getId())
                    .map(Run::getStatus).orElse(null);
            if (afterAdmission != RunStatus.PENDING && afterAdmission != RunStatus.PAUSED) {
                admission.settle(run.getId());
                throw new TaskExecutionException(TaskExecutionException.Cause.ABORTED,
                        "Run " + run.getId() + " left PENDING while queued (" + afterAdmission + "); start aborted");
            }
            eventPublisher.publishEvent(new RunIterationEvent(this, run.getId(), run.getAgentId(),
                    1, run.getMaxIterations()));
        }
```

(Use the method's real local variables — if the agent variable is named differently, keep the same condition; `run` and `eventPublisher` names are verified: field at L95.)

3. Update every `new AgentLoopEngine(` in tests (grep list from the Files section) to pass the new argument; for tests that do not care, pass a `RunAdmissionQueue` built with `new RunAdmissionProperties()` whose both values are set to `0` (unlimited) so existing behavior is unchanged.

- [ ] **Step 4: Run the affected suites**

Run: `cd agent-control-tower && mvn -B test -pl act-execution -Dtest="AgentLoopEngineAdmissionTest,AgentLoopEngineTaskPathTest,AgentLoopEngineCoreDispatchTest,RunAdmissionQueueTest,RunAdmissionQueueEventsTest" -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
Expected: PASS (all classes; Task 4's new assertions included).

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/engine/AgentLoopEngine.java \
        agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/engine/
git commit -m "feat(engine): gate run-owned launches on run admission; publish the start signal at admission"
```

---

### Task 5: Mirror card born TODO again; start signal moves it IN_PROGRESS

**Files:**
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/listener/RunKanbanAutoCreator.java` (remove `.status(KanbanStatus.IN_PROGRESS)` at L131 + rewrite the comment L123-130; keep `startLinkedCards` L152-161 and the settlement TODO-step L212-214)
- Modify: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/listener/RunKanbanAutoCreatorTest.java` (the birth test at L79-101 pins `IN_PROGRESS` at L97)
- Modify: `agent-control-tower/act-app/src/test/java/io/aria/conductor/app/KanbanAutoDispatchIntegrationTest.java` only if Step 4 shows the mid-flight assertion flaking (contingency below)

**Interfaces:**
- Consumes: `RunIterationEvent` start signal from Task 4 (the existing `onRunIteration` handler already performs TODO→IN_PROGRESS at `RunKanbanAutoCreator.java:155-156`).
- Produces: mirror cards born `TODO`; a queued run's card waits in TODO; an admitted run's card flips IN_PROGRESS within the signal; terminal settlement unchanged.

- [ ] **Step 1: Update the birth test (failing first)**

In `RunKanbanAutoCreatorTest`, change the `onRunStarted_createsInProgressItemTitledWithPromptSeed` test:

- rename to `onRunStarted_createsTodoItemTitledWithPromptSeed`
- L97 assertion becomes `assertThat(request.getStatus()).isEqualTo(KanbanStatus.TODO);`
- update the surrounding comment to: the card is born TODO; the admission start signal (`onRunIteration`) moves it to IN_PROGRESS — a queued run stays TODO until its slot frees.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent-control-tower && mvn -B test -pl act-execution -Dtest=RunKanbanAutoCreatorTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
Expected: FAIL (request status is IN_PROGRESS).

- [ ] **Step 3: Implement the birth change**

In `RunKanbanAutoCreator.createCardFor`, replace the `.status(KanbanStatus.IN_PROGRESS)` builder line with `.status(KanbanStatus.TODO)` and replace the comment block above it with:

```java
                // The mirror card is born TODO and waits there honestly while the
                // run queues for an admission slot; the engine's admission start
                // signal (RunIterationEvent) moves it to IN_PROGRESS the moment
                // the run actually starts, and the completion settlement below
                // covers every terminal state (including a card that never left
                // TODO). A linkedRunId still never auto-dispatches.
```

- [ ] **Step 4: Run the full listener + kanban IT suites**

Run: `cd agent-control-tower && mvn -B test -pl act-execution -Dtest="RunKanbanAutoCreatorTest,KanbanListenerNonTransactionalPublishTest" -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
Run: `cd agent-control-tower && mvn -B verify -pl act-app -am -Dskip.unit.tests=true -Dit.test="KanbanAutoDispatchIntegrationTest,KanbanTransitionIntegrityIntegrationTest" -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Djacoco.skip=true`
Expected: PASS. Contingency (only if `KanbanAutoDispatchIntegrationTest`'s first test flakes on its mid-flight assertion): replace the `.isIn(KanbanStatus.IN_PROGRESS, KanbanStatus.REVIEW)` mid-assertion with a single await on the final `REVIEW` state (the second await already present) and delete the now-redundant first await; do not weaken the guard test.

Second contingency (controller ruling 2026-10-03; mandatory): `KanbanAutoDispatchIntegrationTest.runLinkedTodoCardIsNeverAutoDispatched`'s "stays TODO" window is incompatible with the admission start signal (it legitimately moves run-linked TODO cards to IN_PROGRESS, then the card settles). Amend the SCENARIO, not the assertions: await the run reaching terminal (`RunStatus.FAILED`; the test profile fails fast against the closed sandbox port) BEFORE creating the linked TODO card, so no further start signal can touch it. Keep the `during(2s)` predicate unchanged (card remains TODO AND the agent still owns exactly one run) and keep both observables — the status window stays a truthful discriminator of the auto-dispatch guard.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/listener/RunKanbanAutoCreator.java \
        agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/listener/RunKanbanAutoCreatorTest.java \
        agent-control-tower/act-app/src/test/java/io/aria/conductor/app/KanbanAutoDispatchIntegrationTest.java
git commit -m "feat(kanban): mirror cards are born TODO; the admission start signal moves them IN_PROGRESS"
```

---

### Task 6: Cap enforcement integration test (with the latch seam)

**Files:**
- Create: `agent-control-tower/act-app/src/test/java/io/aria/conductor/app/RunAdmissionIntegrationTest.java`

**Interfaces:**
- Consumes: `@SpyBean CoreExecutionService` hold seam (the pattern verified in `KanbanTransitionIntegrityIntegrationTest`), `RunAdmissionProperties` via test properties, run + kanban REST/service beans.
- Produces: deterministic proof that with `aria.runs.max-active=1` a second run stays `PENDING` with its card in `TODO` while the first holds the slot, and starts automatically when the first goes terminal.

- [ ] **Step 1: Write the test**

Structure it like `KanbanAutoDispatchIntegrationTest` (SpringBootTest, `@ActiveProfiles({"test","noop-llm"})`, own H2 URL, `aria.kanban.auto-dispatch-on-create=false`):

```java
@SpringBootTest(
        classes = {ActApplication.class, NoopLlmTestConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:run_admission;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "aria.runs.max-active=1",
                "aria.runs.aria-reserved=0",
                "aria.kanban.auto-dispatch-on-create=false"
        })
@ActiveProfiles({"test", "noop-llm"})
class RunAdmissionIntegrationTest {

    // Autowire RunService, RunRepository, KanbanRepository, AgentRepository, @SpyBean CoreExecutionService.

    @Test
    void overLimitRunsWaitPendingWithTodoCardsAndStartWhenASlotFrees() throws Exception {
        // 1. Create two agents + two runs via RunService (createdAt order defines FIFO).
        // 2. @SpyBean the coordinator: doAnswer(...).when(coreExecutionService).execute(any(), any(), any())
        //    blocks on a per-run latch map (admit first, hold; assert the SECOND never enters execute()).
        // 3. Await: run1 RUNNING (or its card IN_PROGRESS after the start signal);
        //    assert run2.getStatus() == PENDING and its mirror card == TODO after a bounded wait.
        // 4. Release run1's latch -> its execute returns a canned CoreResult; await run1 terminal.
        // 5. Await run2 entering execute() (latch) and its card reaching IN_PROGRESS; release; await terminal.
        //    Final assert: both runs terminal, both cards settled (REVIEW), neither ever had two
        //    active executions simultaneously (the spy counts concurrent execute() entries; assert max 1).
    }
}
```

The canned `CoreResult` construction and the spy seam follow `KanbanTransitionIntegrityIntegrationTest` (read it first; reuse its latch/`CoreResult` pattern).

- [ ] **Step 2: Run to verify the seam catches the old behavior**

Run: `cd agent-control-tower && mvn -B verify -pl act-app -am -Dskip.unit.tests=true -Dit.test="RunAdmissionIntegrationTest" -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Djacoco.skip=true`
Expected: PASS on the implemented stack. (Sanity note: with the gate removed, step 3 would see run2 reach execute immediately — the max-concurrent-entries assertion of step 5 fails; keep that assertion.)

- [ ] **Step 3: Commit**

```bash
git add agent-control-tower/act-app/src/test/java/io/aria/conductor/app/RunAdmissionIntegrationTest.java
git commit -m "test(app): cap enforcement IT - second run waits PENDING/TODO until a slot frees"
```

---

### Task 7: Boot re-enqueue of PENDING runs

**Files:**
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/RunAdmissionBootstrap.java`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/RunAdmissionBootstrapTest.java`

**Interfaces:**
- Consumes: `RunRepository.findByStatus(RunStatus.PENDING)` (`act-agent` repository; runs sorted by `createdAt`), `AgentLoopEngine.startRun(UUID)` (public, `AgentLoopEngine.java:185`).
- Produces: on `ApplicationReadyEvent`, runs left `PENDING` by a restart re-enter the engine (and therefore the admission queue) in `createdAt` order; ordering is AFTER `AgentLoopEngine.recoverOrphanedRuns` (which FAILs only RUNNING/INITIALIZING at `AgentLoopEngine.java:538-563`) so fresh admissions are never reaped.

- [ ] **Step 1: Write the failing test**

```java
package io.aria.conductor.execution.runtime;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.engine.AgentLoopEngine;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunAdmissionBootstrapTest {

    @Test
    void reStartsEveryPendingRunInCreationOrder() {
        RunRepository runs = mock(RunRepository.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        Run older = Run.builder().id(UUID.randomUUID()).status(RunStatus.PENDING)
                .createdAt(Instant.parse("2026-10-03T00:00:00Z")).build();
        Run newer = Run.builder().id(UUID.randomUUID()).status(RunStatus.PENDING)
                .createdAt(Instant.parse("2026-10-03T00:01:00Z")).build();
        when(runs.findByStatus(RunStatus.PENDING)).thenReturn(List.of(newer, older));

        new RunAdmissionBootstrap(runs, engine).onApplicationReady();

        var order = inOrder(engine);
        order.verify(engine).startRun(older.getId());
        order.verify(engine).startRun(newer.getId());
    }

    @Test
    void aFailingRunDoesNotStopTheRest() {
        RunRepository runs = mock(RunRepository.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        Run first = Run.builder().id(UUID.randomUUID()).status(RunStatus.PENDING)
                .createdAt(Instant.parse("2026-10-03T00:00:00Z")).build();
        Run second = Run.builder().id(UUID.randomUUID()).status(RunStatus.PENDING)
                .createdAt(Instant.parse("2026-10-03T00:01:00Z")).build();
        when(runs.findByStatus(RunStatus.PENDING)).thenReturn(List.of(first, second));
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(engine).startRun(first.getId());

        new RunAdmissionBootstrap(runs, engine).onApplicationReady();

        verify(engine).startRun(second.getId());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent-control-tower && mvn -B test -pl act-execution -Dtest=RunAdmissionBootstrapTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
Expected: FAIL (RunAdmissionBootstrap does not exist).

- [ ] **Step 3: Implement the bootstrap**

```java
package io.aria.conductor.execution.runtime;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.engine.AgentLoopEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Objects;

/**
 * Restart safety for the admission queue: runs left PENDING by a previous
 * backend re-enter the engine (and queue) in creation order. Ordered last among
 * ApplicationReadyEvent listeners so AgentLoopEngine.recoverOrphanedRuns (which
 * fails RUNNING/INITIALIZING orphans) can never mis-classify a freshly admitted run.
 */
@Slf4j
@Component
public class RunAdmissionBootstrap {

    private final RunRepository runs;
    private final AgentLoopEngine engine;

    public RunAdmissionBootstrap(RunRepository runs, AgentLoopEngine engine) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void onApplicationReady() {
        runs.findByStatus(RunStatus.PENDING).stream()
                .sorted(Comparator.comparing(Run::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .forEach(run -> {
                    try {
                        engine.startRun(run.getId());
                    } catch (RuntimeException e) {
                        log.warn("Could not re-start queued run {} after boot: {}", run.getId(), e.getMessage());
                    }
                });
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd agent-control-tower && mvn -B test -pl act-execution -Dtest=RunAdmissionBootstrapTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/RunAdmissionBootstrap.java \
        agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/RunAdmissionBootstrapTest.java
git commit -m "feat(runtime): re-enqueue PENDING runs at boot (ordered after the orphan reaper)"
```

---

### Task 8: Module regression and live verification

**Files:** none (verification only).

- [ ] **Step 1: Full module + affected ITs**

Run: `cd agent-control-tower && mvn -B test -pl act-execution -Djacoco.skip=true`
Run: `cd agent-control-tower && mvn -B verify -pl act-app -am -Dskip.unit.tests=true -Dit.test="RunAdmissionIntegrationTest,KanbanAutoDispatchIntegrationTest,KanbanTransitionIntegrityIntegrationTest,KanbanPickupIntegrationTest,KanbanPickupEligibilityIntegrationTest,KanbanHitlMigrationTest" -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Djacoco.skip=true`
Expected: BUILD SUCCESS, 0 failures.

- [ ] **Step 2: Live verification on the local stack** (coordinator-run)

With defaults (6+1): dispatch 8 runs on the local stack; expect 6 executing and 2 `PENDING` with TODO cards; completing one starts the next; no `DOCKER::*` 500s in `.run/backend.log` during the capped fan-out. Evidence: run statuses via `GET /api/v1/runs/{id}`, cards via `GET /api/v1/kanban/items`, log grep `docker::`.

Also verify cancel-while-queued live: while the cap is saturated, cancel a `PENDING` run via the API; it must stay `CANCELLED` (never `INITIALIZING`/`RUNNING`), its card must not reach `IN_PROGRESS`, and the freed slot must admit the next waiter.

- [ ] **Step 3: Push and open the PR** (requires the operator's explicit go-ahead first — the standing push-confirmation rule).

---

## Self-Review (run before executing)

- **Spec coverage:** D1 gateway at engine ✓ Task 4; D2 6+1 config ✓ Task 1 + pool logic Task 2; D3 PENDING + card TODO + start signal ✓ Tasks 2/4/5; D4 deadline freeze at admission ✓ (gate sits above `CoreRunLauncher.execute`, verified L124-134); release/PAUSED semantics ✓ Tasks 2/3; restart re-enqueue ✓ Task 7; cancel-while-queued ✓ Task 3; testing (unit/FIFO/reserve/release/boot/card/live) ✓ Tasks 2-8.
- **Placeholders:** Task 4's test and Task 6 describe the test body as structured steps over an existing verified pattern (both name the exact pattern file and assertions); no TBD/TODO markers elsewhere.
- **Type consistency:** `RunAdmissionProperties.getMaxActive()/getAriaReserved()` used consistently; `RunAdmissionQueue.acquire/release/dequeue/settle/onRunCompleted` names consistent across tasks; `RunIterationEvent` 5-arg ctor matches `RunIterationEvent.java:28`; `RunCompletedEvent(source, runId, agentId, status)` matches `RunService.java:207`.
- **Known judgment calls to watch during execution:** (a) the exact local variable names inside `AgentLoopEngine.startRun` (names may adapt, behavior may not); (b) the `@Order` interplay between the bootstrap and `recoverOrphanedRuns` — if the recovery listener is itself ordered, keep the bootstrap strictly after it; (c) `KanbanAutoDispatchIntegrationTest`'s mid-flight assertion contingency (Task 5 Step 4).
