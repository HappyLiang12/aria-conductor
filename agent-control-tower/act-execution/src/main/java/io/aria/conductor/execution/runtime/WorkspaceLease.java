package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.runtime.WorkspaceKind;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Ownership record of the working directory a run holds: what kind of
 * workspace it is, the platform-owned runtime root, the user-owned source root
 * (never recursively removed by the platform) and the resolved base commit.
 */
public record WorkspaceLease(UUID leaseId, UUID runId,
        WorkspaceKind kind, Path localRoot,
        Path sourceRoot, String runtimeRoot, String baseCommit) {
}
