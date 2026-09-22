package io.aria.conductor.common.runtime;

/**
 * The resolved kind of workspace a run owns, mirroring the spec's workspace
 * classes: Aria-owned {@code SCRATCH}, a managed git {@code WORKTREE}, a
 * {@code DIRECT} lease on a trusted user directory, or a sandbox image
 * {@code SANDBOX_SNAPSHOT} admitted into the run environment.
 */
public enum WorkspaceKind {
    SCRATCH,
    WORKTREE,
    DIRECT,
    SANDBOX_SNAPSHOT
}
