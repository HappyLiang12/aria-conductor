package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.port.RunInputPort;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The run store's input port ({@link RunInputPort}), served by the run input
 * coordinator (2026-10-05 spec §5): a finalize request on the REST surface is
 * delivered to the parked run thread exactly like the operator's answer, and a
 * run not parked in this process is refused (false) so the route answers 409
 * instead of a pretend 202. There is no timeout and no second-chance: the
 * sticky termination intent recorded by the cancel path is consumed by the
 * park itself, never by this port.
 */
@Component
public class CoordinatedRunInputPort implements RunInputPort {

    private final RunInputCoordinator inputs;

    public CoordinatedRunInputPort(RunInputCoordinator inputs) {
        this.inputs = inputs;
    }

    @Override
    public boolean requestFinalize(UUID runId) {
        return inputs.requestFinalize(runId);
    }
}
