package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.AriaConstants;
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
    void completedEventOnAConversationHolderAdmitsThatConversationsNextTurn() throws Exception {
        RunAdmissionProperties properties = new RunAdmissionProperties();
        properties.setMaxActive(6);
        // Three Aria slots, so what holds conv-a's next turn back is the conversation,
        // not the global cap.
        properties.setAriaReserved(3);
        RunAdmissionQueue queue = new RunAdmissionQueue(properties);

        List<Throwable> failures = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();

        UUID parked = UUID.randomUUID();
        CountDownLatch parkedAdmitted = new CountDownLatch(1);
        threads.add(startAria(queue, parked, "2026-11-11T00:00:00Z", "conv-a", parkedAdmitted, failures));
        assertThat(parkedAdmitted.await(5, TimeUnit.SECONDS)).isTrue();

        UUID sameConvRun = UUID.randomUUID();
        CountDownLatch sameConvAdmitted = new CountDownLatch(1);
        threads.add(startAria(queue, sameConvRun, "2026-11-11T00:00:01Z", "conv-a", sameConvAdmitted, failures));
        assertThat(sameConvAdmitted.await(300, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(queue.isWaiting(sameConvRun)).isTrue();

        // Another conversation proceeds while conv-a waits.
        CountDownLatch otherConvAdmitted = new CountDownLatch(1);
        threads.add(startAria(queue, UUID.randomUUID(), "2026-11-11T00:00:02Z", "conv-b", otherConvAdmitted, failures));
        assertThat(otherConvAdmitted.await(5, TimeUnit.SECONDS)).isTrue();

        // The terminal event settles the holder's permit and its conversation with it.
        queue.onRunCompleted(new RunCompletedEvent(this, parked, UUID.randomUUID(), RunStatus.COMPLETED));

        assertThat(sameConvAdmitted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(queue.activeAriaCount()).isEqualTo(2);
        assertThat(failures).isEmpty();
        threads.forEach(Thread::interrupt);
    }

    private static Thread startAria(RunAdmissionQueue queue, UUID runId, String createdAt, String conversationId,
                                    CountDownLatch admitted, List<Throwable> failures) {
        Thread thread = new Thread(() -> {
            try {
                queue.acquire(runId, AriaConstants.ARIA_AGENT_ID, Instant.parse(createdAt), conversationId);
                admitted.countDown();
            } catch (Throwable t) {
                failures.add(t);
            }
        });
        thread.start();
        return thread;
    }

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
