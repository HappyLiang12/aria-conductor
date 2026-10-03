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

    @Test
    void oldestCreatedAtWinsEvenWhenArrivingLater() throws Exception {
        RunAdmissionQueue queue = queue(1, 0);
        List<Throwable> failures = new ArrayList<>();

        CountDownLatch holderAdmitted = new CountDownLatch(1);
        UUID holder = UUID.randomUUID();
        acquiring(queue, holder, WORKER, Instant.parse("2026-10-03T00:00:00Z"), holderAdmitted, failures);
        assertThat(holderAdmitted.await(5, TimeUnit.SECONDS)).isTrue();

        // B arrives first but carries the newer createdAt.
        CountDownLatch bAdmitted = new CountDownLatch(1);
        UUID newer = UUID.randomUUID();
        acquiring(queue, newer, WORKER, Instant.parse("2026-10-03T00:00:02Z"), bAdmitted, failures);
        Thread.sleep(200); // let it park

        // A arrives after B but is older: it must take the freed slot first.
        CountDownLatch aAdmitted = new CountDownLatch(1);
        UUID older = UUID.randomUUID();
        acquiring(queue, older, WORKER, Instant.parse("2026-10-03T00:00:01Z"), aAdmitted, failures);
        Thread.sleep(200); // let it park

        queue.release(holder);
        assertThat(aAdmitted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(bAdmitted.await(300, TimeUnit.MILLISECONDS)).isFalse();

        queue.release(older);
        assertThat(bAdmitted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(failures).isEmpty();
    }

    @Test
    void equalCreatedAtTiesBreakOnRunIdString() throws Exception {
        RunAdmissionQueue queue = queue(1, 0);
        List<Throwable> failures = new ArrayList<>();

        CountDownLatch holderAdmitted = new CountDownLatch(1);
        UUID holder = UUID.randomUUID();
        acquiring(queue, holder, WORKER, Instant.parse("2026-10-03T00:00:00Z"), holderAdmitted, failures);
        assertThat(holderAdmitted.await(5, TimeUnit.SECONDS)).isTrue();

        Instant tiedAt = Instant.parse("2026-10-03T00:00:01Z");
        UUID largerId = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        UUID smallerId = UUID.fromString("00000000-0000-0000-0000-00000000000a");

        // The larger runId arrives first; on equal createdAt the smaller string must win.
        CountDownLatch largerAdmitted = new CountDownLatch(1);
        acquiring(queue, largerId, WORKER, tiedAt, largerAdmitted, failures);
        Thread.sleep(200); // let it park

        CountDownLatch smallerAdmitted = new CountDownLatch(1);
        acquiring(queue, smallerId, WORKER, tiedAt, smallerAdmitted, failures);
        Thread.sleep(200); // let it park

        queue.release(holder);
        assertThat(smallerAdmitted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(largerAdmitted.await(300, TimeUnit.MILLISECONDS)).isFalse();

        queue.release(smallerId);
        assertThat(largerAdmitted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(failures).isEmpty();
    }
}
