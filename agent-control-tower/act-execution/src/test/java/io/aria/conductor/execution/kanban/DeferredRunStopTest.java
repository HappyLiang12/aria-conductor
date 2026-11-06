package io.aria.conductor.execution.kanban;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.aria.conductor.agent.dto.RunResponse;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.service.RunService;
import io.aria.conductor.common.exception.ErrorSurface;
import io.aria.conductor.common.model.RunStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The deferred core stop of a linked run (#111 fix round 2, rulings 5 and 6).
 *
 * <p>Two answers are pinned here, and they are deliberately different. A stop that arrives from
 * an {@code afterCommit} callback protects nothing but an already-committed card move, so a
 * refusal must be recorded and warned, never thrown back at a caller whose transition succeeded
 * (that 409 over a permanently-CANCELLED card was the dead end the filed issue left a live core
 * behind). A stop that arrives from a caller with no committed card move ({@link
 * DeferredRunStop#runNow}) has no such protection to grant, and its caller's answer IS the
 * refusal: {@code RunService.cancelRun}'s exact reason is the #111 guarantee that a live core
 * which did not confirm its stop is never answered as a cancellation.
 *
 * <p>Deterministic throughout: every case but {@link #productionWorker_...} injects a same-thread
 * or recording {@link Executor}, and the production worker is driven with latches (a start signal
 * and a release gate), never with sleeps.
 */
class DeferredRunStopTest {

    private static final UUID RUN_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final String REFUSAL =
            "Run " + RUN_ID + ": the run-owned core refused the stop (writer tree still live)";

    private RunService runService;
    private RunRepository runRepository;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        runService = mock(RunService.class);
        runRepository = mock(RunRepository.class);
        appender = new ListAppender<>();
        appender.start();
        logger().addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger().detachAppender(appender);
    }

    private static Logger logger() {
        return (Logger) LoggerFactory.getLogger(DeferredRunStop.class);
    }

    /** The worker with an injected executor: same-thread unless a test says otherwise. */
    private DeferredRunStop stop(Executor executor) {
        return new DeferredRunStop(runService, runRepository, executor);
    }

    private DeferredRunStop sameThreadStop() {
        return stop(Runnable::run);
    }

    private static RunResponse cancelled(UUID runId) {
        return RunResponse.builder().id(runId).status(RunStatus.CANCELLED).build();
    }

    /** The recorded messages at one level, in emission order. */
    private List<String> messages(Level level) {
        List<String> out = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            if (level.equals(event.getLevel())) {
                out.add(event.getFormattedMessage());
            }
        }
        return out;
    }

    /**
     * The durable pending-stop intent, written through the real conditional repository method with
     * the shared cancelable-status authority, carrying the refusal reason in its text.
     */
    private void verifyPendingStopRecorded(UUID runId, String reasonFragment) {
        verify(runRepository).recordPendingStopIfCancelable(eq(runId),
                argThat(message -> message != null && message.contains(reasonFragment)),
                eq(DeferredRunStop.PENDING_STOP_CODE), eq(DeferredRunStop.PENDING_STOP_SURFACE),
                any(Instant.class), eq(RunService.cancelableStatuses()));
    }

    // ---- runNow: a caller with no committed card move keeps owning the refusal ----

    @Test
    void runNow_refusedStop_recordsThePendingStopAndRethrowsTheExactRefusal() {
        IllegalStateException refusal = new IllegalStateException(REFUSAL);
        when(runService.cancelRun(RUN_ID)).thenThrow(refusal);

        assertThatThrownBy(() -> sameThreadStop().runNow(RUN_ID))
                .as("a caller with no committed card move to protect is answered with the refusal "
                        + "it asked for -- RunService.cancelRun's exact reason is the #111 guarantee")
                .isSameAs(refusal);

        verifyPendingStopRecorded(RUN_ID, REFUSAL);
        assertThat(messages(Level.WARN))
                .anySatisfy(warn -> assertThat(warn)
                        .contains(RUN_ID.toString())
                        .contains(REFUSAL));
    }

    @Test
    void runNow_verifiedStop_recordsNothingAndReturns() {
        when(runService.cancelRun(RUN_ID)).thenReturn(cancelled(RUN_ID));

        assertThatCode(() -> sameThreadStop().runNow(RUN_ID)).doesNotThrowAnyException();

        verify(runService).cancelRun(RUN_ID);
        verifyNoInteractions(runRepository);
        assertThat(messages(Level.WARN)).isEmpty();
    }

    // ---- submit: the committed card move is never answered with the refusal ----

    @Test
    void submit_refusedStop_recordsAndWarnsAndNeverThrowsIntoTheCommittedCardMove() {
        when(runService.cancelRun(RUN_ID)).thenThrow(new IllegalStateException(REFUSAL));

        assertThatCode(() -> sameThreadStop().submit(RUN_ID))
                .as("the card is already CANCELLED and the board will not re-run the move, so the "
                        + "refusal must not travel back out of afterCommit as a 409")
                .doesNotThrowAnyException();

        verifyPendingStopRecorded(RUN_ID, REFUSAL);
        assertThat(messages(Level.WARN))
                .anySatisfy(warn -> assertThat(warn).contains(RUN_ID.toString()).contains(REFUSAL));
    }

    @Test
    void submit_verifiedStop_recordsNothing() {
        when(runService.cancelRun(RUN_ID)).thenReturn(cancelled(RUN_ID));

        sameThreadStop().submit(RUN_ID);

        verify(runService).cancelRun(RUN_ID);
        verifyNoInteractions(runRepository);
        assertThat(messages(Level.WARN)).isEmpty();
    }

    @Test
    void submit_rejectedQueue_degradesToTheSameDurableRecordInsteadOfDroppingTheStop() {
        String rejection = "Task rejected fromThreadPoolExecutor[Running, Capacity = 100]";
        Executor overflowing = task -> {
            throw new RejectedExecutionException(rejection);
        };

        assertThatCode(() -> stop(overflowing).submit(RUN_ID)).doesNotThrowAnyException();

        verify(runService, never()).cancelRun(any());
        verifyPendingStopRecorded(RUN_ID, "the stop queue is full (" + rejection + ")");
        assertThat(messages(Level.WARN))
                .anySatisfy(warn -> assertThat(warn)
                        .contains(RUN_ID.toString())
                        .contains("could not be queued")
                        .contains(rejection));
    }

    @Test
    void submit_recordWriteFailing_isReportedAndNeverKillsTheStopWorker() {
        when(runService.cancelRun(RUN_ID)).thenThrow(new IllegalStateException(REFUSAL));
        when(runRepository.recordPendingStopIfCancelable(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("connection dropped"));

        assertThatCode(() -> sameThreadStop().submit(RUN_ID)).doesNotThrowAnyException();

        assertThat(messages(Level.ERROR))
                .anySatisfy(error -> assertThat(error)
                        .contains(RUN_ID.toString())
                        .contains("connection dropped")
                        .contains("retry sweep"));
    }

    @Test
    void submit_terminalCommittedRun_reportsThePendingStopWasNotRecordedInsteadOfClaimingItWas() {
        when(runService.cancelRun(RUN_ID)).thenThrow(new IllegalStateException(REFUSAL));
        // The conditional write's 0 answer: the run finalized meanwhile, so there is no live core.
        when(runRepository.recordPendingStopIfCancelable(any(), any(), any(), any(), any(), any()))
                .thenReturn(0);

        sameThreadStop().submit(RUN_ID);

        assertThat(messages(Level.WARN))
                .anySatisfy(warn -> assertThat(warn)
                        .contains(RUN_ID.toString())
                        .contains("not recorded")
                        .contains("terminal"));
    }

    // ---- the retry sweep ----

    @Test
    void retryPendingStops_reDrivesEveryRunStillCarryingTheMarkerOnTheStopWorker() {
        UUID second = UUID.fromString("66666666-6666-6666-6666-666666666666");
        when(runRepository.findIdsPendingStop(DeferredRunStop.PENDING_STOP_CODE,
                RunService.cancelableStatuses())).thenReturn(List.of(RUN_ID, second));
        when(runService.cancelRun(RUN_ID)).thenReturn(cancelled(RUN_ID));
        when(runService.cancelRun(second)).thenThrow(new IllegalStateException("still refusing"));
        // The second run's refusal is recorded again, so the next sweep still sees it.
        when(runRepository.recordPendingStopIfCancelable(eq(second), any(), any(), any(), any(), any()))
                .thenReturn(1);

        sameThreadStop().retryPendingStops();

        verify(runRepository).findIdsPendingStop(DeferredRunStop.PENDING_STOP_CODE,
                RunService.cancelableStatuses());
        verify(runService).cancelRun(RUN_ID);
        verify(runService).cancelRun(second);
        verifyPendingStopRecorded(second, "still refusing");
    }

    @Test
    void retryPendingStops_readFailureEndsTheSweepQuietlyAndLeavesTheNextOneIntact() {
        when(runRepository.findIdsPendingStop(any(), any()))
                .thenThrow(new RuntimeException("database unavailable"));

        assertThatCode(() -> sameThreadStop().retryPendingStops()).doesNotThrowAnyException();

        verify(runService, never()).cancelRun(any());
        assertThat(messages(Level.ERROR))
                .anySatisfy(error -> assertThat(error).contains("database unavailable"));
    }

    @Test
    void retryPendingStops_nothingMarked_drivesNoCancel() {
        when(runRepository.findIdsPendingStop(DeferredRunStop.PENDING_STOP_CODE,
                RunService.cancelableStatuses())).thenReturn(List.of());

        sameThreadStop().retryPendingStops();

        verify(runService, never()).cancelRun(any());
        verifyNoInteractions(runService);
    }

    // ---- the production worker itself: one stop at a time, bounded, overflow never dropped ----

    @Test
    void productionWorker_runsOneStopAtATimeAndDegradesTheQueueOverflowToTheDurableRecord()
            throws InterruptedException {
        // The 2-arg constructor is the production wiring: the bounded single-thread worker.
        DeferredRunStop stop = new DeferredRunStop(runService, runRepository);
        CountDownLatch firstStopInFlight = new CountDownLatch(1);
        CountDownLatch releaseStops = new CountDownLatch(1);
        CountDownLatch allAcceptedStopsDone = new CountDownLatch(1 + DeferredRunStop.QUEUE_CAPACITY);
        AtomicInteger stopsInFlight = new AtomicInteger();
        AtomicInteger maxConcurrentStops = new AtomicInteger();
        when(runService.cancelRun(any())).thenAnswer(invocation -> {
            firstStopInFlight.countDown();
            releaseStops.await(30, TimeUnit.SECONDS);
            maxConcurrentStops.accumulateAndGet(stopsInFlight.incrementAndGet(), Math::max);
            stopsInFlight.decrementAndGet();
            allAcceptedStopsDone.countDown();
            return cancelled(invocation.getArgument(0));
        });
        when(runRepository.recordPendingStopIfCancelable(any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        UUID busy = UUID.randomUUID();
        stop.submit(busy);
        assertThat(firstStopInFlight.await(30, TimeUnit.SECONDS))
                .as("the worker picked up the first stop")
                .isTrue();

        List<UUID> queued = new ArrayList<>();
        for (int i = 0; i < DeferredRunStop.QUEUE_CAPACITY; i++) {
            UUID runId = UUID.randomUUID();
            queued.add(runId);
            stop.submit(runId);
        }
        UUID overflow = UUID.randomUUID();
        stop.submit(overflow);

        releaseStops.countDown();
        assertThat(allAcceptedStopsDone.await(30, TimeUnit.SECONDS))
                .as("the queued stops all ran once the worker was released")
                .isTrue();

        // Verified after the drain (no mock call in flight on another thread): the full queue is
        // the durable record, not a dropped stop, and not a cancel attempt either.
        for (UUID runId : queued) {
            verify(runService).cancelRun(runId);
        }
        verify(runService, never()).cancelRun(overflow);
        verifyPendingStopRecorded(overflow, "the stop queue is full");
        assertThat(maxConcurrentStops.get())
                .as("one stop at a time: a burst of card cancels never occupies more than one "
                        + "connection, which is what ruling 6 is for")
                .isEqualTo(1);
    }

    // ---- the durable intent's identity ----

    @Test
    void pendingStopIntent_carriesTheRuntimeSurfaceAndCodeTheRunViewsAlreadySurface() {
        when(runService.cancelRun(RUN_ID)).thenThrow(new IllegalStateException(REFUSAL));

        assertThatThrownBy(() -> sameThreadStop().runNow(RUN_ID)).isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> surface = ArgumentCaptor.forClass(String.class);
        verify(runRepository).recordPendingStopIfCancelable(eq(RUN_ID), any(), code.capture(),
                surface.capture(), any(Instant.class), eq(RunService.cancelableStatuses()));
        assertThat(code.getValue()).isEqualTo(DeferredRunStop.PENDING_STOP_CODE);
        assertThat(surface.getValue())
                .as("the intent is a runtime problem the operator can act on, not a credential one")
                .isEqualTo(ErrorSurface.RUNTIME.name());
    }
}
