package io.aria.conductor.common.port;

import java.util.UUID;

/**
 * Port for a live run parked on an operator decision (2026-11-23 live bridge spec
 * §"Tools, approvals, live run"). A live turn has no engine thread to wake: the run
 * is a row the socket owns, and the parked state is only ever released by the
 * operator answering the approval or finalizing the run.
 *
 * <p>{@link RunInputPort#requestFinalize} consults these parks before the engine's
 * pending input, so {@code POST /runs/{id}/finalize} answers 202 for a live park
 * exactly as it does for a coordinated run — and {@code unpark} takes the run back
 * out of that reach (a closed socket, or an approval that was answered instead)
 * without settling it, so the route honestly refuses with 409 again.
 */
public interface LiveRunPark {

    /** No park at all: for a deployment without the execution runtime. */
    LiveRunPark NONE = new LiveRunPark() {
        @Override
        public void park(UUID runId, Runnable onFinalize) {
        }

        @Override
        public boolean unpark(UUID runId) {
            return false;
        }

        @Override
        public boolean parked(UUID runId) {
            return false;
        }
    };

    /**
     * Registers the work that finalizing this run runs, in place of a previously
     * registered one. The callback runs at most once, on the thread that called
     * {@link RunInputPort#requestFinalize}.
     */
    void park(UUID runId, Runnable onFinalize);

    /** @return true when a park was standing and is now removed, so its callback never runs */
    boolean unpark(UUID runId);

    /** @return true while a finalize would reach this run */
    boolean parked(UUID runId);
}
