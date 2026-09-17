package io.aria.conductor.mcp.tools;

import io.aria.conductor.agent.dto.CreateAgentRequest;
import io.aria.conductor.agent.dto.UpdateAgentRequest;
import io.aria.conductor.agent.service.AgentService;
import io.aria.conductor.common.exception.InvalidStateTransitionException;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.execution.mcp.McpProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Agent lifecycle tools. Thin wrappers over the same AgentService the REST
 * controllers call — REST/dashboard and MCP stay at parity. Error types mirror
 * GlobalExceptionHandler's REST status mapping (409 CONFLICT for
 * InvalidStateTransitionException/IllegalStateException).
 */
@Component
@RequiredArgsConstructor
public class AgentTools implements McpTool {

    private final AgentService agentService;
    private final McpProperties mcpProperties;

    @Tool(name = "list_agents",
            description = "List active agents (retired agents excluded). Use get_agent for full details of one agent.")
    public String listAgents() {
        try {
            return ToolResponses.ok(agentService.listAgents());
        } catch (Exception e) {
            return ToolResponses.error("AGENT_LIST_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "get_agent",
            description = "Get an agent by id: name, type (NATIVE/ADK), role, model, provider, healthStatus, skills and tools.")
    public String getAgent(@ToolParam(description = "Agent id") UUID id) {
        try {
            return ToolResponses.ok(agentService.getAgent(id));
        } catch (ResourceNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("AGENT_READ_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "create_agent",
            description = "Create an agent. agentType is optional and defaults to NATIVE; ADK agents run on the Python runtime.")
    public String createAgent(
            @ToolParam(description = "Agent name") String name,
            @ToolParam(description = "Agent role, e.g. orchestrator, worker, developer", required = false) String role,
            @ToolParam(description = "AgentType: NATIVE or ADK; blank defaults to NATIVE", required = false) String agentType,
            @ToolParam(description = "Agent description", required = false) String description,
            @ToolParam(description = "LLM model id", required = false) String model,
            @ToolParam(description = "LLM provider name", required = false) String provider,
            @ToolParam(description = "ADK provider name, e.g. langchain", required = false) String adkProvider,
            @ToolParam(description = "Extra agent config", required = false) Map<String, Object> config) {
        try {
            AgentType type = agentType == null || agentType.isBlank()
                    ? AgentType.NATIVE
                    : parseAgentType(agentType);
            CreateAgentRequest request = CreateAgentRequest.builder()
                    .name(name)
                    .description(description)
                    .agentType(type)
                    .role(role)
                    .model(model)
                    .provider(provider)
                    .adkProvider(adkProvider)
                    .config(config)
                    .build();
            return ToolResponses.ok(agentService.createAgent(request));
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("AGENT_CREATE_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "update_agent",
            description = "Update an agent. Only the fields you supply are applied; omitted fields keep their current value.")
    public String updateAgent(
            @ToolParam(description = "Agent id") UUID id,
            @ToolParam(description = "New name", required = false) String name,
            @ToolParam(description = "New description", required = false) String description,
            @ToolParam(description = "New role", required = false) String role,
            @ToolParam(description = "New LLM model id", required = false) String model,
            @ToolParam(description = "New LLM provider name", required = false) String provider,
            @ToolParam(description = "New ADK provider name", required = false) String adkProvider,
            @ToolParam(description = "Replacement agent config", required = false) Map<String, Object> config) {
        try {
            UpdateAgentRequest request = UpdateAgentRequest.builder()
                    .name(name)
                    .description(description)
                    .role(role)
                    .model(model)
                    .provider(provider)
                    .adkProvider(adkProvider)
                    .config(config)
                    .build();
            return ToolResponses.ok(agentService.updateAgent(id, request));
        } catch (ResourceNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (InvalidStateTransitionException | IllegalStateException e) {
            return ToolResponses.error("CONFLICT", e.getMessage(), e, mcpProperties.isDebug());
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("AGENT_UPDATE_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "retire_agent",
            description = "Retire an agent (soft delete). Retired agents are excluded from list_agents and cannot start new runs.")
    public String retireAgent(@ToolParam(description = "Agent id") UUID id) {
        try {
            return ToolResponses.ok(agentService.retireAgent(id));
        } catch (ResourceNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (InvalidStateTransitionException | IllegalStateException e) {
            return ToolResponses.error("CONFLICT", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("AGENT_RETIRE_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    private static AgentType parseAgentType(String raw) {
        try {
            return AgentType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid agentType '" + raw + "'. Valid: NATIVE, ADK");
        }
    }
}
