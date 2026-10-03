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
}
