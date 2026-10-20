package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.exception.ActionableIllegalArgumentException;
import io.aria.conductor.common.exception.ErrorSurface;
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
            throw new ActionableIllegalArgumentException(
                    "Unsupported agent core: " + coreId
                    + ". Supported cores: " + supportedCores()
                    + ". Select one in Configure > Runtime.",
                    "UNSUPPORTED_AGENT_CORE", ErrorSurface.RUNTIME);
        if (!catalog.supports(coreId, executionMode))
            throw new ActionableIllegalArgumentException(
                    "Unsupported execution mode: " + coreId + "/" + executionMode
                    + ". " + coreId + " supports: " + supportedModes(coreId)
                    + ". Select the fleet runtime in Configure > Runtime.",
                    "UNSUPPORTED_EXECUTION_MODE", ErrorSurface.RUNTIME);

        // An explicit mode is never discarded: it is validated against the path.
        // Worktree is only the applied default, it is not inferred as a replacement.
        WorkspaceMode workspaceMode = requested.workspaceMode() != null ? requested.workspaceMode()
                : requested.workspacePath() == null ? null : WorkspaceMode.WORKTREE;
        validateWorkspace(workspaceMode, requested.workspacePath(), requested.workspaceBaseRef());

        return new AgentExecutionSettings(coreId, executionMode, workspaceMode,
                requested.workspacePath(), requested.workspaceBaseRef());
    }

    private String supportedCores() {
        return catalog.coreIds().stream().sorted()
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private String supportedModes(String coreId) {
        return catalog.modesFor(coreId).stream().map(Enum::name).sorted()
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static void validateWorkspace(WorkspaceMode workspaceMode, String workspacePath, String workspaceBaseRef) {
        if (workspaceMode == null) {
            // No repository binding (e.g. an operator chat using an owned scratch workspace).
            if (workspaceBaseRef != null)
                throw WorkspaceGuidance.refusal("Base ref requires a worktree workspace."
                        + WorkspaceGuidance.SETTINGS_HINT, "WORKSPACE_BASE_REF_REQUIRES_WORKTREE");
            return;
        }
        boolean hasDirectory = workspacePath != null && !workspacePath.isBlank();
        if (workspaceMode == WorkspaceMode.DIRECT) {
            if (!hasDirectory)
                throw WorkspaceGuidance.refusal("Direct workspace requires an explicitly selected directory."
                        + WorkspaceGuidance.SETTINGS_HINT, "WORKSPACE_DIRECT_REQUIRED");
            if (workspaceBaseRef != null)
                throw WorkspaceGuidance.refusal("Base ref applies only to a worktree workspace."
                        + WorkspaceGuidance.SETTINGS_HINT, "WORKSPACE_BASE_REF_INVALID_FOR_DIRECT");
            return;
        }
        if (!hasDirectory)
            throw WorkspaceGuidance.refusal("Worktree workspace requires a repository path."
                    + WorkspaceGuidance.SETTINGS_HINT, "WORKSPACE_REPOSITORY_REQUIRED");
        if (workspaceBaseRef != null && workspaceBaseRef.isBlank())
            throw WorkspaceGuidance.refusal("Worktree base ref must not be blank."
                    + WorkspaceGuidance.SETTINGS_HINT, "WORKSPACE_BASE_REF_BLANK");
    }
}
