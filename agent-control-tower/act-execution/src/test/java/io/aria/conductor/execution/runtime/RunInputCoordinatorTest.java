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
}
