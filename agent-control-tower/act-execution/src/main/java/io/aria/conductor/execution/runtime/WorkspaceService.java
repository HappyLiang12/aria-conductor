package io.aria.conductor.execution.runtime;

/**
 * Ownership of a run's working directory: acquire a lease, capture the
 * artifacts once all writers have verifiably stopped, and release. Retention
 * of captured artifacts is separate from unlocking the workspace.
 */
public interface WorkspaceService {

    WorkspaceLease acquire(ExecutionSpec spec);

    ArtifactBundle capture(WorkspaceLease lease, ExecutionBackend backend,
            RuntimeHandle handle, StopProof proof);

    void release(WorkspaceLease lease, StopProof proof);
}
