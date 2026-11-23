package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.port.LiveRunPark;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The live park served by the run input coordinator (2026-11-23 live bridge spec
 * §"Tools, approvals, live run"). It exists so the run store (act-agent) can answer
 * {@code POST /runs/{id}/finalize} for a run a phone socket parked without depending on
 * the execution runtime, exactly as {@link CoordinatedRunInputPort} does for a run an
 * engine thread parked.
 */
@Component
public class LiveRunParkAdapter implements LiveRunPark {

    private final RunInputCoordinator inputs;

    public LiveRunParkAdapter(RunInputCoordinator inputs) {
        this.inputs = inputs;
    }

    @Override
    public void park(UUID runId, Runnable onFinalize) {
        inputs.parkLive(runId, onFinalize);
    }

    @Override
    public boolean unpark(UUID runId) {
        return inputs.unparkLive(runId);
    }

    @Override
    public boolean parked(UUID runId) {
        return inputs.liveParked(runId);
    }
}
