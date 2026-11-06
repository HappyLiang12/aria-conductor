package io.aria.conductor.common.port;

import java.util.UUID;

/**
 * Port interface for the runtime-owned control of a run (Task 19, fix round 7;
 * cancel added for #111 fix round 1, ownership restated for #111 fix round 2).
 * Lives in act-common to avoid a circular module dependency: the run store
 * (act-agent) persists a run's PAUSED/RUNNING/CANCELLED state, and for a run
 * whose runtime is owned by the execution coordinator (act-execution) the state
 * may only be persisted after the runtime verified the pause/resume/cancel --
 * the coordinator's verified {@code ControlAck} gates the persisted state, so a
 * pause or cancel the run-owned core never confirmed can never be answered as
 * PAUSED/CANCELLED.
 *
 * <p>A run no deployment owns (the legacy paths, a run whose coordinator already
 * verified its stop) keeps the plain recorded transition; {@link #owns} is the
 * discriminator.
 */
public interface RunRuntimeControlPort {

    /**
     * True when the implementing component owns the run's runtime: its runtime
     * record is live in the coordinator's registry and its writers were not
     * verified stopped yet. Registry presence is the whole test -- a run whose
     * launch is still in flight (handle launched, core session opening) is owned,
     * because {@link #cancel} has a real core process to stop. A run the
     * coordinator no longer owns is not controllable here, and its caller keeps
     * the recorded transition.
     */
    boolean owns(UUID agentId, UUID runId);

    /**
     * Verifies the pause of the run's owned runtime.
     *
     * <p>Pause suspends the run-owned core session, so it requires a runtime whose
     * session is open: an owned run still inside its launch window is refused with
     * that exact reason rather than recorded as PAUSED.
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
     * <p>Actionable for every owned runtime, including one whose core session has
     * not opened yet: the coordinator then tells the in-flight launch attempt to
     * stop and stops, destroys and releases the core its launch already created,
     * and answers only after that stop was verified.
     *
     * @throws IllegalStateException when the run-owned runtime did not confirm a
     *         verified stop; the message carries the exact refusal reason, and this
     *         port persisted nothing. The caller may still record the terminal
     *         transition when the runtime later stops being {@link #owns owned}
     *         (it finalized or vanished while the stop was in flight), never while
     *         an owned runtime refuses the stop.
     */
    void cancel(UUID runId);

    /**
     * Verifies the resume on the same owned runtime, and so requires a runtime
     * whose core session is open (see {@link #pause}).
     *
     * @throws IllegalStateException when the runtime did not confirm the resume;
     *         the message carries the exact refusal reason, and nothing was
     *         persisted on the caller's side
     */
    void resume(UUID runId);
}
