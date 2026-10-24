package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.service.RunService;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.test.DataJpaTestBase;
import io.aria.conductor.test.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The durable pending-stop intent of {@link DeferredRunStop} against real H2 (#111 fix round 2,
 * ruling 5): the claims here are about SQL semantics — a conditional UPDATE's WHERE clause and the
 * retry sweep's read — so they run through the same {@link DataJpaTestBase} slice as
 * {@code RunRepositoryCancellationIntegrationTest} instead of a stubbed repository.
 *
 * <p>What a mock cannot show is exactly the point: only the database evaluates
 * {@code Run.status IN :cancelable} against the committed row, so only here can a test prove that
 * a run which finalized while the verified stop was in flight is left alone (its terminal state
 * AND its own failure identity), and that the sweep's read can never hand a finished run back to
 * the stop worker.
 *
 * <p>{@code RunService} is the mocked collaborator (its verified stop is another class's
 * behaviour, pinned by {@code RunServiceTest} and {@code DeferredRunStopTest}); the repository and
 * the SQL are real. The stop worker is the same-thread executor, so every call lands inside the
 * test method deterministically.
 */
class DeferredRunStopPendingStopSliceTest extends DataJpaTestBase {

    /** The engine's terminal set, as {@code RunRepository.completeIfNotTerminal} expects it. */
    private static final List<RunStatus> TERMINAL = List.of(
            RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.ABORTED);

    @Autowired
    RunRepository runRepository;

    private RunService runService;

    private DeferredRunStop stop() {
        runService = mock(RunService.class);
        return new DeferredRunStop(runService, runRepository, Runnable::run);
    }

    private Run persist(RunStatus status) {
        Run run = TestDataBuilder.aRun().withStatus(status).build();
        entityManager.persist(run);
        return run;
    }

    private Run reload(UUID id) {
        flushAndClear();
        return entityManager.find(Run.class, id);
    }

    @Test
    void pendingStopIntent_isNotWrittenOntoARunThatWasCancelledMeanwhile() {
        Run run = persist(RunStatus.RUNNING);
        flushAndClear();
        // The run finalizes between the refused stop and the record attempt: the real
        // conditional terminal write, the one cancelRun publishes.
        assertThat(runRepository.cancelIfCancelable(run.getId(), RunStatus.CANCELLED,
                Instant.parse("2026-10-24T13:00:00Z"), Instant.parse("2026-10-24T13:00:00Z"),
                RunService.cancelableStatuses())).isEqualTo(1);
        flushAndClear();

        stop().recordPendingStop(run.getId(), "the writers were not verified stopped");

        Run after = reload(run.getId());
        assertThat(after.getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(after.getCompletedAt()).isEqualTo(Instant.parse("2026-10-24T13:00:00Z"));
        assertThat(after.getErrorCode()).isNull();
        assertThat(after.getErrorMessage()).isNull();
        assertThat(after.getErrorSurface()).isNull();
    }

    @Test
    void pendingStopIntent_neverOverwritesTheFailureIdentityOfARunThatCompletedMeanwhile() {
        Run run = persist(RunStatus.RUNNING);
        flushAndClear();
        assertThat(runRepository.completeIfNotTerminal(run.getId(), RunStatus.FAILED, 4, 80L,
                Instant.parse("2026-10-24T13:05:00Z"), null, "the core died on its own",
                "CORE_DIED", "RUNTIME", Instant.parse("2026-10-24T13:05:00Z"), TERMINAL)).isEqualTo(1);
        flushAndClear();

        stop().recordPendingStop(run.getId(), "the queue is full");

        Run after = reload(run.getId());
        assertThat(after.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(after.getErrorCode()).isEqualTo("CORE_DIED");
        assertThat(after.getErrorMessage()).isEqualTo("the core died on its own");
        assertThat(after.getErrorSurface()).isEqualTo("RUNTIME");
    }

    @Test
    void pendingStopIntent_isRecordedOnEveryCancelableStatusWithoutMovingTheStatus() {
        List<Run> live = RunService.cancelableStatuses().stream()
                .map(this::persist)
                .toList();
        flushAndClear();
        DeferredRunStop stop = stop();

        for (Run run : live) {
            stop.recordPendingStop(run.getId(), "stop refused: writers unverified");
        }

        for (Run run : live) {
            Run after = reload(run.getId());
            assertThat(after.getStatus())
                    .as("a pending stop leaves the run live, so the sweep keeps targeting it")
                    .isEqualTo(run.getStatus());
            assertThat(after.getErrorCode()).isEqualTo(DeferredRunStop.PENDING_STOP_CODE);
            assertThat(after.getErrorSurface()).isEqualTo(DeferredRunStop.PENDING_STOP_SURFACE);
            assertThat(after.getErrorMessage())
                    .contains("Core stop pending")
                    .contains("stop refused: writers unverified");
        }
    }

    @Test
    void pendingStopIntent_isRefusedForAStatusTheCancelCouldNotHaveOverwrittenEither() {
        // The guard is the SAME authority the terminal write uses, so the sweep can never be
        // handed a run whose row a cancel could not move.
        List<RunStatus> notCancelable = new java.util.ArrayList<>(TERMINAL);
        notCancelable.removeAll(RunService.cancelableStatuses());
        assertThat(notCancelable).isNotEmpty();
        DeferredRunStop stop = stop();

        for (RunStatus status : notCancelable) {
            Run run = persist(status);
            run.setErrorCode("PRE_EXISTING");
            flushAndClear();

            stop.recordPendingStop(run.getId(), "stop refused");

            Run after = reload(run.getId());
            assertThat(after.getStatus()).isEqualTo(status);
            assertThat(after.getErrorCode()).isEqualTo("PRE_EXISTING");
        }
    }

    @Test
    void pendingStopIntent_onAMissingRunWritesNothing() {
        UUID ghost = UUID.randomUUID();

        stop().recordPendingStop(ghost, "stop refused");

        flushAndClear();
        assertThat(runRepository.findById(ghost)).isEmpty();
    }

    @Test
    void retrySweep_reDrivesTheLiveMarkedRunAndLeavesTheTerminalMarkedRunAlone() {
        Run liveMarked = persist(RunStatus.RUNNING);
        Run terminalMarked = persist(RunStatus.CANCELLED);
        Run liveUnmarked = persist(RunStatus.RUNNING);
        for (Run run : List.of(liveMarked, terminalMarked)) {
            run.setErrorCode(DeferredRunStop.PENDING_STOP_CODE);
            run.setErrorMessage("Core stop pending: stop refused");
        }
        liveUnmarked.setErrorCode("SOME_OTHER_CODE");
        flushAndClear();

        stop().retryPendingStops();

        verify(runService).cancelRun(liveMarked.getId());
        verify(runService, never()).cancelRun(terminalMarked.getId());
        verify(runService, never()).cancelRun(liveUnmarked.getId());
        // The terminal run keeps its marker as history: the sweep reads it away, it never rewrites
        // or clears a finished row.
        Run terminal = reload(terminalMarked.getId());
        assertThat(terminal.getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(terminal.getErrorCode()).isEqualTo(DeferredRunStop.PENDING_STOP_CODE);
    }

    @Test
    void retrySweep_readsExactlyTheCancelableRunsCarryingTheMarker() {
        Run marked = persist(RunStatus.WAITING_INPUT);
        marked.setErrorCode(DeferredRunStop.PENDING_STOP_CODE);
        Run markedButFinished = persist(RunStatus.COMPLETED);
        markedButFinished.setErrorCode(DeferredRunStop.PENDING_STOP_CODE);
        flushAndClear();

        List<UUID> pending = runRepository.findIdsPendingStop(DeferredRunStop.PENDING_STOP_CODE,
                RunService.cancelableStatuses());

        assertThat(pending).containsExactly(marked.getId());
    }
}
