package io.aria.conductor.execution.engine;

import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD: cancelRun() MUST NOT overwrite a COMPLETED run back to CANCELLED.
 * Bug: ctx==null branch unconditionally writes CANCELLED, corrupting completed runs.
 * Fix: guard — only cancel if status is RUNNING or PAUSED.
 */
class CancelRunCompletedRunTest {

    private static boolean canCancel(RunStatus status) {
        return status == RunStatus.RUNNING || status == RunStatus.PAUSED;
    }

    @Test
    void completedRunShouldNotBeCancelled() {
        Run run = new Run();
        run.setId(UUID.randomUUID());
        run.setStatus(RunStatus.COMPLETED);

        assertThat(canCancel(run.getStatus()))
                .as("COMPLETED run should NOT be overwritten by cancelRun")
                .isFalse();
    }

    @Test
    void runningRunShouldBeCancellable() {
        Run run = new Run();
        run.setId(UUID.randomUUID());
        run.setStatus(RunStatus.RUNNING);

        assertThat(canCancel(run.getStatus()))
                .as("RUNNING run should be cancellable")
                .isTrue();
    }

    @Test
    void failedRunShouldNotBeCancelled() {
        Run run = new Run();
        run.setId(UUID.randomUUID());
        run.setStatus(RunStatus.FAILED);

        assertThat(canCancel(run.getStatus()))
                .as("FAILED run should NOT be overwritten")
                .isFalse();
    }

    @Test
    void pausedRunShouldBeCancellable() {
        Run run = new Run();
        run.setId(UUID.randomUUID());
        run.setStatus(RunStatus.PAUSED);

        assertThat(canCancel(run.getStatus()))
                .as("PAUSED run should be cancellable")
                .isTrue();
    }
}
