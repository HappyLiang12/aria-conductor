package io.aria.conductor.aria.tools.handlers;

import io.aria.conductor.agent.dto.AgentResponse;
import io.aria.conductor.agent.dto.CreateAgentRequest;
import io.aria.conductor.agent.dto.UpdateAgentRequest;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.LlmProviderRepository;
import io.aria.conductor.agent.service.AgentService;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.LlmProvider;
import io.aria.conductor.execution.tool.ToolHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component("agentToolHandler")
public class AgentToolHandler implements ToolHandler {

    private final AgentService agentService;
    private final AgentRepository agentRepository;
    private final LlmProviderRepository llmProviderRepository;

    public AgentToolHandler(AgentService agentService, AgentRepository agentRepository,
                            LlmProviderRepository llmProviderRepository) {
        this.agentService = agentService;
        this.agentRepository = agentRepository;
        this.llmProviderRepository = llmProviderRepository;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String toolName = Objects.toString(arguments.get("toolName"), "");
        try {
            return switch (toolName) {
                case "list_agents" -> listAgents();
                case "get_agent" -> getAgent(arguments);
                case "create_agent" -> createAgent(arguments);
                case "update_agent" -> updateAgent(arguments);
                case "retire_agent" -> retireAgent(arguments);
                case "delete_agent" -> retireAgent(arguments);
                default -> error("Unknown tool: " + toolName);
            };
        } catch (Exception e) {
            log.error("AgentToolHandler failed for {}", toolName, e);
            return error(e.getMessage());
        }
    }

    private String listAgents() {
        List<Agent> agents = agentRepository.findAll().stream()
                .filter(a -> a.getHealthStatus() != HealthStatus.RETIRED)
                .toList();
        StringBuilder sb = new StringBuilder("Agents (" + agents.size() + " total):\n");
        for (Agent a : agents) {
            sb.append("  - ").append(a.getName())
                    .append(" | ID: ").append(a.getId())
                    .append(" | Type: ").append(a.getAgentType() != null ? a.getAgentType().name() : "UNKNOWN")
                    .append(" | Status: ").append(a.getHealthStatus() != null ? a.getHealthStatus().name() : "UNKNOWN")
                    .append(" | Role: ").append(a.getRole() != null ? a.getRole() : "N/A")
                    .append("\n");
        }
        return sb.toString().trim();
    }

    /** Resolve an agent id-or-name to a UUID: try UUID first, else look up by name. Null if unresolved. */
    static UUID resolveAgentId(AgentRepository agentRepository, String idOrName) {
        if (idOrName == null || idOrName.isBlank()) return null;
        try {
            return UUID.fromString(idOrName.trim());
        } catch (IllegalArgumentException e) {
            return agentRepository.findByName(idOrName.trim()).map(Agent::getId).orElse(null);
        }
    }

    private String getAgent(Map<String, Object> args) {
        String id = Objects.toString(args.get("id"), "");
        if (id.isEmpty()) return error("Missing required parameter: id");
        UUID uuid = resolveAgentId(agentRepository, id);
        if (uuid == null) return error("Agent not found: " + id);
        Agent a = agentRepository.findById(uuid).orElse(null);
        if (a == null) return error("Agent not found: " + id);
        StringBuilder sb = new StringBuilder("Agent: ").append(a.getName()).append("\n");
        sb.append("  ID: ").append(a.getId()).append("\n");
        sb.append("  Role: ").append(a.getRole()).append("\n");
        sb.append("  Description: ").append(a.getDescription() != null ? a.getDescription() : "N/A").append("\n");
        sb.append("  Status: ").append(a.getHealthStatus() != null ? a.getHealthStatus().name() : "UNKNOWN");
        return sb.toString();
    }

    private String createAgent(Map<String, Object> args) {
        String name = Objects.toString(args.get("name"), "");
        String role = Objects.toString(args.get("role"), "");
        if (name.isEmpty()) return error("Missing required parameter: name");
        if (role.isEmpty()) return error("Missing required parameter: role");

        // Default model/provider from the active LLM provider so the created agent is runnable (#34).
        String model = Objects.toString(args.get("model"), "");
        String provider = Objects.toString(args.get("provider"), "");
        if (model.isBlank() || provider.isBlank()) {
            LlmProvider active = llmProviderRepository.findByActiveTrue().orElse(null);
            if (active == null) {
                return error("No active LLM provider configured; cannot create a runnable agent. "
                        + "Configure one (POST /api/v1/llm-providers) or pass model and provider explicitly.");
            }
            if (model.isBlank()) model = active.getDefaultModel();
            if (provider.isBlank()) provider = active.getName();
        }

        CreateAgentRequest req = CreateAgentRequest.builder()
                .name(name).role(role)
                .description(Objects.toString(args.get("description"), ""))
                .agentType(AgentType.NATIVE)
                .model(model)
                .provider(provider)
                .adkProvider("langchain")
                .build();
        AgentResponse resp = agentService.createAgent(req);
        return "Agent '" + name + "' created (id: " + resp.getId() + ", model: " + model + ")";
    }

    private String updateAgent(Map<String, Object> args) {
        String id = Objects.toString(args.get("id"), "");
        if (id.isEmpty()) return error("Missing required parameter: id");
        UUID uuid = UUID.fromString(id);
        UpdateAgentRequest req = UpdateAgentRequest.builder()
                .name(Objects.toString(args.get("name"), null))
                .description(Objects.toString(args.get("description"), null))
                .role(Objects.toString(args.get("role"), null))
                .build();
        AgentResponse resp = agentService.updateAgent(uuid, req);
        return "Agent '" + resp.getName() + "' updated (id: " + resp.getId() + ")";
    }

    private String retireAgent(Map<String, Object> args) {
        String id = Objects.toString(args.get("id"), "");
        if (id.isEmpty()) return error("Missing required parameter: id");
        UUID uuid = UUID.fromString(id);
        agentService.retireAgent(uuid);
        return "Agent " + id + " retired successfully.";
    }

    private String error(String msg) {
        return "Error: " + msg;
    }
}
