package io.aria.conductor.common.runtime;

/**
 * How a Host run obtains its working directory: a platform-managed git
 * {@code WORKTREE} created from the admitted repository, or a {@code DIRECT}
 * lease on an explicitly selected trusted backend-local directory. Worktree is
 * the default when a Host repository is configured; Direct is never inferred.
 */
public enum WorkspaceMode {
    WORKTREE,
    DIRECT
}
