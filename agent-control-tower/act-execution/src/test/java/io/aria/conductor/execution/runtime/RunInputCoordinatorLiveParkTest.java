package io.aria.conductor.execution.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The live park (2026-11-23 live bridge spec §"Tools, approvals, live run"): a live
 * turn has no engine thread to wake, so its parked run is released by a callback the
 * socket registers rather than by a future an engine blocks on. Finalize must reach
 * that callback exactly once — a second request is the honest 409, never a replay.
 */
class RunInputCoordinatorLiveParkTest {

    private final RunInputCoordinator coordinator =
            new RunInputCoordinator(mock(ApplicationEventPublisher.class));

    @Test
    void aParkedLiveRunIsWokenOnceByFinalize() {
        UUID runId = UUID.randomUUID();
        AtomicInteger finalizes = new AtomicInteger();
        coordinator.parkLive(runId, finalizes::incrementAndGet);

        assertThat(coordinator.liveParked(runId)).isTrue();
        assertThat(coordinator.requestFinalize(runId)).isTrue();
        assertThat(finalizes.get()).isEqualTo(1);

        assertThat(coordinator.requestFinalize(runId)).isFalse();
        assertThat(finalizes.get()).isEqualTo(1);
        assertThat(coordinator.liveParked(runId)).isFalse();
    }

    @Test
    void anUnparkedLiveRunIsNeverWoken() {
        UUID runId = UUID.randomUUID();
        AtomicInteger finalizes = new AtomicInteger();
        coordinator.parkLive(runId, finalizes::incrementAndGet);

        assertThat(coordinator.unparkLive(runId)).isTrue();
        assertThat(coordinator.liveParked(runId)).isFalse();
        assertThat(coordinator.requestFinalize(runId)).isFalse();
        assertThat(finalizes.get()).isZero();
        assertThat(coordinator.unparkLive(runId)).isFalse();
    }

    /**
     * A live park is a park: finalize consults it before the engine's pending input, so
     * one route serves both. The pending entry is untouched — the live run has no engine
     * thread, and no run is in both states at once.
     */
    @Test
    void finalizeConsultsTheLiveParkBeforeThePendingEngineInput() {
        UUID runId = UUID.randomUUID();
        AtomicInteger finalizes = new AtomicInteger();
        coordinator.requestInput(runId, "which database?");
        coordinator.parkLive(runId, finalizes::incrementAndGet);

        assertThat(coordinator.requestFinalize(runId)).isTrue();

        assertThat(finalizes.get()).isEqualTo(1);
    }

    /** A run nothing parked is still refused, live or not. */
    @Test
    void finalizeForAnUnknownRunStaysFalse() {
        assertThat(coordinator.requestFinalize(UUID.randomUUID())).isFalse();
        assertThat(coordinator.unparkLive(UUID.randomUUID())).isFalse();
        assertThat(coordinator.liveParked(UUID.randomUUID())).isFalse();
    }

    @Test
    void aSecondParkForTheSameRunReplacesTheFirstCallback() {
        UUID runId = UUID.randomUUID();
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        coordinator.parkLive(runId, first::incrementAndGet);
        coordinator.parkLive(runId, second::incrementAndGet);

        assertThat(coordinator.requestFinalize(runId)).isTrue();

        assertThat(first.get()).isZero();
        assertThat(second.get()).isEqualTo(1);
    }
}
