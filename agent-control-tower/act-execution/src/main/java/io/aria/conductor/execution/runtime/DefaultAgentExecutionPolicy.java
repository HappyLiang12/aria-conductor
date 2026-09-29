package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.runtime.AgentExecutionPolicy;
import io.aria.conductor.common.runtime.AgentExecutionSettings;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.runtime.WorkspaceMode;

import java.util.Objects;

/**
 * Pure admission/normalization policy for agent execution settings (plan
 * section 3.1).
 *
 * <p>Omitted values on a new agent resolve to the documented defaults
 * ({@value #DEFAULT_CORE_ID} + {@value #DEFAULT_EXECUTION_MODE}); an explicitly
 * unknown, removed, or unsupported core/mode is rejected with a precise error
 * and is never defaulted or substituted for another core/mode. Workspace
 * selection is validated structurally: a worktree never falls back to Direct,
 * Direct requires an explicitly selected directory, and a base ref is only
 * meaningful for a worktree.
 *
 * <p>The policy resolves no credentials and holds no launch collaborator;
 * credential/config readiness is checked separately by the caller and a missing
 * credential never causes a core or mode substitution. Git-level repository and
 * base-ref resolution belongs to workspace admission, so this policy only
 * rejects structurally contradictory workspace fields.
 */
public final class DefaultAgentExecutionPolicy implements AgentExecutionPolicy {

    /** Documented default core for a new agent that omits the core. */
    static final String DEFAULT_CORE_ID = "opencode";

    /** Documented default placement for a new agent that omits the mode. */
    static final ExecutionMode DEFAULT_EXECUTION_MODE = ExecutionMode.SANDBOX;

    private final CoreCatalog catalog;

    public DefaultAgentExecutionPolicy(CoreCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "Core catalog is required");
    }

    @Override
    public AgentExecutionSettings normalize(AgentExecutionSettings requested) {
        Objects.requireNonNull(requested, "Agent execution settings are required");

        String coreId = requested.coreId() == null ? DEFAULT_CORE_ID : requested.coreId();
        ExecutionMode executionMode = requested.executionMode() == null
                ? DEFAULT_EXECUTION_MODE : requested.executionMode();

        if (!catalog.coreIds().contains(coreId))
            throw new IllegalArgumentException("Unsupported agent core: " + coreId);
        if (!catalog.supports(coreId, executionMode))
            throw new IllegalArgumentException("Unsupported execution mode: " + coreId + "/" + executionMode);

        // An explicit mode is never discarded: it is validated against the path.
        // Worktree is only the applied default, it is not inferred as a replacement.
        WorkspaceMode workspaceMode = requested.workspaceMode() != null ? requested.workspaceMode()
                : requested.workspacePath() == null ? null : WorkspaceMode.WORKTREE;
        validateWorkspace(workspaceMode, requested.workspacePath(), requested.workspaceBaseRef());

        return new AgentExecutionSettings(coreId, executionMode, workspaceMode,
                requested.workspacePath(), requested.workspaceBaseRef());
    }

    private static void validateWorkspace(WorkspaceMode workspaceMode, String workspacePath, String workspaceBaseRef) {
        if (workspaceMode == null) {
            // No repository binding (e.g. an operator chat using an owned scratch workspace).
            if (workspaceBaseRef != null)
                throw new IllegalArgumentException("Base ref requires a worktree workspace");
            return;
        }
        boolean hasDirectory = workspacePath != null && !workspacePath.isBlank();
        if (workspaceMode == WorkspaceMode.DIRECT) {
            if (!hasDirectory)
                throw new IllegalArgumentException("Direct workspace requires an explicitly selected directory");
            if (workspaceBaseRef != null)
                throw new IllegalArgumentException("Base ref applies only to a worktree workspace");
            return;
        }
        if (!hasDirectory)
            throw new IllegalArgumentException("Worktree workspace requires a repository path");
        if (workspaceBaseRef != null && workspaceBaseRef.isBlank())
            throw new IllegalArgumentException("Worktree base ref must not be blank");
    }
}
