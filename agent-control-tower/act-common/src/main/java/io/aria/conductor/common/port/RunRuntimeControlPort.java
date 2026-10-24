package io.aria.conductor.common.port;

import java.util.UUID;

/**
 * Port interface for the runtime-owned control of a run (Task 19, fix round 7;
 * cancel added for #111 fix round 1). Lives in act-common to avoid a circular
 * module dependency: the run store (act-agent) persists a run's
 * PAUSED/RUNNING/CANCELLED state, and for a run whose runtime is owned by the
 * execution coordinator (act-execution) the state may only be persisted after
 * the runtime verified the pause/resume/cancel -- the coordinator's verified
 * {@code ControlAck} gates the persisted state, so a pause or cancel the
 * run-owned core never confirmed can never be answered as PAUSED/CANCELLED.
 *
 * <p>A run no deployment owns (the legacy paths) keeps the plain recorded
 * transition; {@link #owns} is the discriminator.
 */
public interface RunRuntimeControlPort {

    /**
     * True when the run's runtime is owned by the implementing component and
     * still has a usable session/handle, so a pause/resume/cancel of it must be
     * verified through this port rather than merely recorded. A registry entry
     * that outlives its session/handle is not controllable: its caller records
     * the transition instead of demanding a stop the runtime cannot perform.
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
     * Verifies the stop of the run's owned runtime before the caller records
     * CANCELLED.
     *
     * @throws IllegalStateException when the run-owned runtime did not confirm a
     *         verified stop; the message carries the exact refusal reason, and
     *         nothing was persisted on the caller's side
     */
    void cancel(UUID runId);

    /**
     * Verifies the resume on the same owned runtime.
     *
     * @throws IllegalStateException when the runtime did not confirm the resume;
     *         the message carries the exact refusal reason, and nothing was
     *         persisted on the caller's side
     */
    void resume(UUID runId);
}
