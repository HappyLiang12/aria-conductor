package io.aria.conductor.agent.dto;

import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.runtime.WorkspaceMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateAgentRequest {

    @NotBlank(message = "Agent name is required")
    private String name;

    private String description;

    @NotNull(message = "Agent type is required")
    private AgentType agentType;

    private String role;

    private String model;

    private String provider;

    private String adkProvider;

    /** Where the runs execute; omitted values default to {@code SANDBOX}. */
    private ExecutionMode executionMode;

    /** How a Host run obtains its working directory; {@code WORKTREE} when a path is given. */
    private WorkspaceMode workspaceMode;

    /** The admitted repository (worktree) or explicitly selected directory (direct). */
    private String workspacePath;

    /** Optional base ref used when creating a worktree. */
    private String workspaceBaseRef;

    private Map<String, Object> config;
}
