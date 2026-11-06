package io.aria.conductor.agent.repository;

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

/**
 * The atomic terminal writes behind #111 fix round 1, against real H2:
 * {@link RunRepository#cancelIfCancelable} and
 * {@link RunRepository#completeIfNotTerminal} evaluate the run's committed
 * status inside the UPDATE, so a completed/failed run can never be overwritten
 * by a cancel (and vice versa) no matter which writer commits first.
 */
class RunRepositoryCancellationIntegrationTest extends DataJpaTestBase {

    /** Mirrors {@code RunService.cancelableStatuses()}: every status whose transitions allow CANCELLED. */
    private static final List<RunStatus> CANCELABLE = List.of(
            RunStatus.PENDING, RunStatus.INITIALIZING, RunStatus.RUNNING,
            RunStatus.PAUSED, RunStatus.WAITING_INPUT, RunStatus.ABORTED);
    /** Mirrors the engine's private TERMINAL_STATUSES. */
    private static final List<RunStatus> TERMINAL = List.of(
            RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.ABORTED);

    @Autowired
    RunRepository repository;

    private Run persist(RunStatus status) {
        Run run = TestDataBuilder.aRun().withStatus(status).build();
        entityManager.persist(run);
        return run;
    }

    @Test
    void cancelIfCancelable_movesEveryCancelableStatusToCancelledWithTheCompletedTimestamp() {
        Instant completedAt = Instant.parse("2026-10-24T10:15:30Z");
        Instant updatedAt = Instant.parse("2026-10-24T10:15:31Z");
        List<UUID> ids = CANCELABLE.stream().map(status -> persist(status).getId()).toList();
        flushAndClear();

        for (UUID id : ids) {
            assertThat(repository.cancelIfCancelable(id, RunStatus.CANCELLED, completedAt, updatedAt,
                    CANCELABLE)).isEqualTo(1);
        }
        flushAndClear();

        for (UUID id : ids) {
            Run reloaded = entityManager.find(Run.class, id);
            assertThat(reloaded.getStatus()).isEqualTo(RunStatus.CANCELLED);
            assertThat(reloaded.getCompletedAt()).isEqualTo(completedAt);
            assertThat(reloaded.getUpdatedAt()).isEqualTo(updatedAt);
        }
    }

    @Test
    void cancelIfCancelable_refusesCompletedFailedAndCancelledRuns() {
        Instant now = Instant.parse("2026-10-24T10:20:00Z");
        for (RunStatus status : List.of(RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED)) {
            Run run = persist(status);
            flushAndClear();

            assertThat(repository.cancelIfCancelable(run.getId(), RunStatus.CANCELLED, now, now,
                    CANCELABLE)).isZero();
            flushAndClear();

            Run reloaded = entityManager.find(Run.class, run.getId());
            assertThat(reloaded.getStatus()).isEqualTo(status);
            assertThat(reloaded.getCompletedAt()).isNull();
        }
    }

    @Test
    void cancelIfCancelable_refusesAMissingRun() {
        assertThat(repository.cancelIfCancelable(UUID.randomUUID(), RunStatus.CANCELLED,
                Instant.parse("2026-10-24T10:25:00Z"), Instant.parse("2026-10-24T10:25:00Z"),
                CANCELABLE)).isZero();
    }

    @Test
    void completeIfNotTerminal_writesTheCompletionPayloadOntoANonTerminalRun() {
        Instant completedAt = Instant.parse("2026-10-24T11:00:00Z");
        Instant updatedAt = Instant.parse("2026-10-24T11:00:01Z");
        Run run = persist(RunStatus.RUNNING);
        flushAndClear();

        assertThat(repository.completeIfNotTerminal(run.getId(), RunStatus.COMPLETED, 3, 42L,
                completedAt, "the answer", null, null, null, updatedAt, TERMINAL)).isEqualTo(1);
        flushAndClear();

        Run reloaded = entityManager.find(Run.class, run.getId());
        assertThat(reloaded.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(reloaded.getIterationCount()).isEqualTo(3);
        assertThat(reloaded.getTotalTokensUsed()).isEqualTo(42L);
        assertThat(reloaded.getCompletedAt()).isEqualTo(completedAt);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(reloaded.getFinalOutput()).isEqualTo("the answer");
        assertThat(reloaded.getErrorMessage()).isNull();
        assertThat(reloaded.getErrorCode()).isNull();
        assertThat(reloaded.getErrorSurface()).isNull();
    }

    @Test
    void completeIfNotTerminal_preservesExistingOutputAndFailureIdentityWhenNullsArePassed() {
        Instant completedAt = Instant.parse("2026-10-24T11:10:00Z");
        Run run = persist(RunStatus.RUNNING);
        run.setFinalOutput("earlier output");
        run.setErrorMessage("existing failure");
        run.setErrorCode("OLD_CODE");
        run.setErrorSurface("CREW");
        entityManager.flush();
        flushAndClear();

        assertThat(repository.completeIfNotTerminal(run.getId(), RunStatus.FAILED, 1, 5L,
                completedAt, null, null, null, null, completedAt, TERMINAL)).isEqualTo(1);
        flushAndClear();

        Run reloaded = entityManager.find(Run.class, run.getId());
        assertThat(reloaded.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(reloaded.getFinalOutput()).isEqualTo("earlier output");
        assertThat(reloaded.getErrorMessage()).isEqualTo("existing failure");
        assertThat(reloaded.getErrorCode()).isEqualTo("OLD_CODE");
        assertThat(reloaded.getErrorSurface()).isEqualTo("CREW");
    }

    @Test
    void completeIfNotTerminal_refusesEveryTerminalStatus() {
        Instant now = Instant.parse("2026-10-24T11:20:00Z");
        for (RunStatus status : TERMINAL) {
            Run run = persist(status);
            run.setFinalOutput("terminal output");
            entityManager.flush();
            flushAndClear();

            assertThat(repository.completeIfNotTerminal(run.getId(), RunStatus.COMPLETED, 9, 99L,
                    now, "overwrite attempt", "err", "CODE", "SURFACE", now, TERMINAL)).isZero();
            flushAndClear();

            Run reloaded = entityManager.find(Run.class, run.getId());
            assertThat(reloaded.getStatus()).isEqualTo(status);
            assertThat(reloaded.getFinalOutput()).isEqualTo("terminal output");
            assertThat(reloaded.getErrorMessage()).isNull();
            assertThat(reloaded.getIterationCount()).isZero();
            assertThat(reloaded.getTotalTokensUsed()).isZero();
        }
    }

    @Test
    void cancelAndCompletionAreMutuallyExclusive_inBothOrderings() {
        Instant first = Instant.parse("2026-10-24T12:00:00Z");
        Instant second = Instant.parse("2026-10-24T12:00:01Z");

        // Ordering A: the cancel commits first; the engine's completion then loses.
        Run cancelledFirst = persist(RunStatus.RUNNING);
        flushAndClear();
        assertThat(repository.cancelIfCancelable(cancelledFirst.getId(), RunStatus.CANCELLED,
                first, first, CANCELABLE)).isEqualTo(1);
        assertThat(repository.completeIfNotTerminal(cancelledFirst.getId(), RunStatus.COMPLETED,
                7, 70L, second, "late output", null, null, null, second, TERMINAL)).isZero();
        flushAndClear();
        Run afterCancel = entityManager.find(Run.class, cancelledFirst.getId());
        assertThat(afterCancel.getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(afterCancel.getFinalOutput()).isNull();
        assertThat(afterCancel.getCompletedAt()).isEqualTo(first);

        // Ordering B: the engine commits first; the cancel then loses.
        Run completedFirst = persist(RunStatus.RUNNING);
        flushAndClear();
        assertThat(repository.completeIfNotTerminal(completedFirst.getId(), RunStatus.COMPLETED,
                2, 20L, first, "the answer", null, null, null, first, TERMINAL)).isEqualTo(1);
        assertThat(repository.cancelIfCancelable(completedFirst.getId(), RunStatus.CANCELLED,
                second, second, CANCELABLE)).isZero();
        flushAndClear();
        Run afterComplete = entityManager.find(Run.class, completedFirst.getId());
        assertThat(afterComplete.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(afterComplete.getFinalOutput()).isEqualTo("the answer");
        assertThat(afterComplete.getCompletedAt()).isEqualTo(first);
    }
}
