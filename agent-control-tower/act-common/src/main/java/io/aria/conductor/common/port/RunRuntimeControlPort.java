package io.aria.conductor.common.port;

import java.util.UUID;

/**
 * Port interface for the runtime-owned control of a run (Task 19, fix round 7).
 * Lives in act-common to avoid a circular module dependency: the run store
 * (act-agent) persists a run's PAUSED/RUNNING state, and for a run whose runtime
 * is owned by the execution coordinator (act-execution) the state may only be
 * persisted after the runtime verified the pause/resume -- the coordinator's
 * verified {@code ControlAck} gates the persisted state, so a pause the
 * run-owned core never confirmed can never be answered as PAUSED.
 *
 * <p>A run no deployment owns (the legacy paths) keeps the plain recorded
 * transition; {@link #owns} is the discriminator.
 */
public interface RunRuntimeControlPort {

    /**
     * True when the run's runtime is owned by the implementing component, so a
     * pause/resume of it must be verified through this port rather than merely
     * recorded.
     */
    boolean owns(UUID agentId, UUID runId);

    /**
     * Verifies the pause of the run's owned runtime.
     *
     * @throws IllegalStateException when the runtime did not confirm the pause;
     *         the message carries the exact refusal reason, and nothing was
     *         persisted on the caller's side
     */
    void pause(UUID runId);

    /**
     * Verifies the resume on the same owned runtime.
     *
     * @throws IllegalStateException when the runtime did not confirm the resume;
     *         the message carries the exact refusal reason, and nothing was
     *         persisted on the caller's side
     */
    void resume(UUID runId);
}
