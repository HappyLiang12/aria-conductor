package io.aria.conductor.execution.runtime;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * The one owner of run finalization and the only writer of terminal artifact
 * state. The order is fixed -- stop all writers owned by the run, verify the
 * stop, export/capture, destroy the environment, release the locks -- and a
 * prompt-completion event is never a stop proof: the proof this class carries is
 * exactly the record the backend returned, never one minted here.
 *
 * <p>A refused stop is retried inside the caller's cleanup window and is never
 * followed by a destroy: destroying after a refused stop is terminal for that
 * run's writers, so the environment and the workspace lease are left in place
 * and the caller is told, explicitly, that nothing was captured, destroyed or
 * released. An already elapsed window is not retried at all -- no control state
 * may be claimed for a window that cannot be honoured. The window is measured
 * against the injected {@link Clock}, the same time source the caller used to
 * compute the deadline; never against the wall clock.
 */
public final class RunFinalizer {

    /** Bounded retries of a refused stop inside the caller's own window. */
    static final int MAX_STOP_ATTEMPTS = 2;

    private final WorkspaceService workspaces;
    private final Clock clock;

    /**
     * @param clock the clock the caller's cleanup window is measured against;
     *              the caller and this finalizer must share one time source
     */
    public RunFinalizer(WorkspaceService workspaces, Clock clock) {
        this.workspaces = Objects.requireNonNull(workspaces, "Workspace service is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    /**
     * Stops, verifies, captures, destroys and releases one run.
     *
     * @param backend  the placement backend that owns the runtime
     * @param handle   the launched run-owned runtime
     * @param lease    the run's workspace lease
     * @param deadline the caller's own cleanup window
     * @return the captured artifact bundle (the terminal artifact state)
     * @throws IllegalStateException when the stop could not be verified; nothing
     *         is captured, destroyed or released in that case
     */
    public ArtifactBundle finish(ExecutionBackend backend, RuntimeHandle handle,
            WorkspaceLease lease, Instant deadline) {
        Objects.requireNonNull(backend, "Execution backend is required");
        Objects.requireNonNull(handle, "Runtime handle is required");
        Objects.requireNonNull(lease, "Workspace lease is required");
        Objects.requireNonNull(deadline, "Cleanup deadline is required");

        StopProof proof = stopVerified(backend, handle, deadline);
        ArtifactBundle bundle = workspaces.capture(lease, backend, handle, proof);
        backend.destroy(handle);
        workspaces.release(lease, proof);
        return bundle;
    }

    /**
     * Stop all owned writers and verify it. The proof carried forward is the
     * backend's own record; a missing or foreign proof is refused, and a refused
     * stop is retried only while the caller's window still runs.
     */
    private StopProof stopVerified(ExecutionBackend backend, RuntimeHandle handle, Instant deadline) {
        StopProof proof = null;
        for (int attempt = 0; attempt < MAX_STOP_ATTEMPTS; attempt++) {
            if (attempt > 0 && !clock.instant().isBefore(deadline)) {
                break; // an elapsed window cannot be retried into a verified state
            }
            proof = backend.stopWriters(handle, deadline);
            if (proof == null) {
                throw new IllegalStateException("The backend returned no stop proof for run " + handle.runId()
                        + "; nothing was captured, destroyed or released");
            }
            if (!proof.runId().equals(handle.runId())) {
                throw new IllegalStateException("Stop proof run " + proof.runId()
                        + " does not match the finalized run " + handle.runId()
                        + "; nothing was captured, destroyed or released");
            }
            if (proof.allWritersStopped()) {
                return proof;
            }
        }
        boolean elapsed = !clock.instant().isBefore(deadline);
        throw new IllegalStateException("Run " + handle.runId() + " was not finalized: the backend did not verify"
                + " that all writers stopped (" + proof + (elapsed ? "; the cleanup window elapsed" : "")
                + "); nothing was captured, destroyed or released");
    }
}
