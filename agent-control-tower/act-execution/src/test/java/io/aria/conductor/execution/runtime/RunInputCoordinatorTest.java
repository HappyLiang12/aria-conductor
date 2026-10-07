package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.event.RunWaitingForInputEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RunInputCoordinatorTest {

    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final RunInputCoordinator coordinator = new RunInputCoordinator(publisher);

    @Test
    void requestInputPublishesTheWaitingEventAndParksUntilAnAnswerArrives() throws Exception {
        UUID runId = UUID.randomUUID();
        CompletableFuture<RunInputCoordinator.OperatorInput> future = coordinator.requestInput(runId, "Which DB?");

        verify(publisher).publishEvent(any(RunWaitingForInputEvent.class));
        assertThat(future).isNotDone();

        coordinator.submitAnswer(runId, "postgres");

        RunInputCoordinator.OperatorInput input = future.get(1, TimeUnit.SECONDS);
        assertThat(input.answer()).isEqualTo("postgres");
        assertThat(input.finalizeRequested()).isFalse();
    }

    @Test
    void finalizeCompletesTheParkedFutureWithTheSignal() throws Exception {
        UUID runId = UUID.randomUUID();
        CompletableFuture<RunInputCoordinator.OperatorInput> future = coordinator.requestInput(runId, "q?");

        assertThat(coordinator.requestFinalize(runId)).isTrue();

        assertThat(future.get(1, TimeUnit.SECONDS).finalizeRequested()).isTrue();
    }

    @Test
    void submitAnswerOnARunThatIsNotWaitingIsRefused() {
        assertThatThrownBy(() -> coordinator.submitAnswer(UUID.randomUUID(), "x"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(coordinator.requestFinalize(UUID.randomUUID())).isFalse();
    }

    @Test
    void aSecondRequestForTheSameRunReplacesTheParkedFuture() throws Exception {
        UUID runId = UUID.randomUUID();
        CompletableFuture<RunInputCoordinator.OperatorInput> first = coordinator.requestInput(runId, "q1?");
        CompletableFuture<RunInputCoordinator.OperatorInput> second = coordinator.requestInput(runId, "q2?");

        coordinator.submitAnswer(runId, "the answer");

        assertThat(second.get(1, TimeUnit.SECONDS).answer()).isEqualTo("the answer");
        assertThat(first).isNotDone();
    }

    @Test
    void afterAnAnswerIsAcceptedTheRunIsNoLongerWaiting() throws Exception {
        UUID runId = UUID.randomUUID();
        CompletableFuture<RunInputCoordinator.OperatorInput> future = coordinator.requestInput(runId, "q?");

        coordinator.submitAnswer(runId, "postgres");
        assertThat(future.get(1, TimeUnit.SECONDS).answer()).isEqualTo("postgres");

        assertThatThrownBy(() -> coordinator.submitAnswer(runId, "again"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(coordinator.requestFinalize(runId)).isFalse();
    }

    @Test
    void afterAFinalizeSignalTheCoordinatorRefusesAFurtherFinalize() throws Exception {
        UUID runId = UUID.randomUUID();
        CompletableFuture<RunInputCoordinator.OperatorInput> future = coordinator.requestInput(runId, "q?");

        assertThat(coordinator.requestFinalize(runId)).isTrue();
        assertThat(future.get(1, TimeUnit.SECONDS).finalizeRequested()).isTrue();

        assertThat(coordinator.requestFinalize(runId)).isFalse();
    }

    // ── sticky termination intent (Plan B task 6 ruling 1) ──────────────

    @Test
    void aRecordedTerminationIntentMakesTheNextRequestInputReturnTheFinalizeSignalWithoutPublishing() {
        // A cancel landing between the loop's turn result and the park call finds
        // no pending ask (requestFinalize would return false and the run would park
        // forever). The sticky intent makes the imminent requestInput hand back the
        // finalize signal immediately -- the run is already terminal in the DB, so
        // no RunWaitingForInputEvent may be published.
        UUID runId = UUID.randomUUID();
        coordinator.recordTerminationIntent(runId);

        CompletableFuture<RunInputCoordinator.OperatorInput> future = coordinator.requestInput(runId, "Which DB?");

        assertThat(future).isCompleted();
        assertThat(future.join().finalizeRequested()).isTrue();
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void theStickyTerminationIntentIsConsumedExactlyOnce() {
        UUID runId = UUID.randomUUID();
        coordinator.recordTerminationIntent(runId);
        CompletableFuture<RunInputCoordinator.OperatorInput> first = coordinator.requestInput(runId, "q1?");
        assertThat(first).isCompleted();

        // The intent is spent: a genuine second park must behave like today.
        CompletableFuture<RunInputCoordinator.OperatorInput> second = coordinator.requestInput(runId, "q2?");

        assertThat(second).isNotDone();
        verify(publisher, times(1)).publishEvent(any(RunWaitingForInputEvent.class));
    }

    @Test
    void recordTerminationIntentWakesAParkedRunLikeRequestFinalize() throws Exception {
        UUID runId = UUID.randomUUID();
        CompletableFuture<RunInputCoordinator.OperatorInput> future = coordinator.requestInput(runId, "q?");

        coordinator.recordTerminationIntent(runId);

        assertThat(future.get(1, TimeUnit.SECONDS).finalizeRequested()).isTrue();
    }
}
