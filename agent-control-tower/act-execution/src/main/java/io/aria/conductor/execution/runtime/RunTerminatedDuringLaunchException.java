package io.aria.conductor.execution.runtime;

import java.util.UUID;

/**
 * A run-owned launch that observed its run already terminal (#111 fix round 2).
 *
 * <p>It is thrown only after the launched core was stopped and released through the verified
 * abort path, and it always extends {@link IllegalStateException} so every existing refusal
 * handler keeps working. The engine maps it to the run's already-terminal outcome: the launch
 * attempt neither prompts nor records a second terminal transition, so a committed CANCELLED is
 * never relabelled FAILED and never publishes a second completion event.
 */
public class RunTerminatedDuringLaunchException extends IllegalStateException {

    public RunTerminatedDuringLaunchException(UUID runId) {
        super("Run " + runId + " is already terminal in the run store while its run-owned core was"
                + " still launching; the launched core was stopped and released and the attempt"
                + " never prompted");
    }
}
