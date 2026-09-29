package io.aria.conductor.common.runtime;

/**
 * The requested core/mode/workspace selection of an agent, as stored on
 * {@code agents} and frozen into a run's execution binding before launch.
 * Values are carried verbatim: normalization (defaulting, validation) belongs
 * to {@link AgentExecutionPolicy}, not to this snapshot.
 *
 * <p>Every component is nullable so legacy rows keep explicitly unknown
 * metadata instead of an invented default.
 */
public record AgentExecutionSettings(String coreId, ExecutionMode executionMode,
        WorkspaceMode workspaceMode, String workspacePath, String workspaceBaseRef) {
}
