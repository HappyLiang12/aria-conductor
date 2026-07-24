package io.aria.conductor.execution.tool;

import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.ToolDefinition;
import io.aria.conductor.common.model.VersionStatus;
import io.aria.conductor.common.repository.AgentToolRepository;
import io.aria.conductor.common.repository.RoleToolTemplateRepository;
import io.aria.conductor.common.repository.ToolDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentToolResolver {
    private final ToolDefinitionRepository toolRepo;
    private final AgentToolRepository agentToolRepo;
    private final RoleToolTemplateRepository roleTemplateRepo;

    public List<ToolDefinition> resolveForAgent(Agent agent) {
        String agentId = agent.getId().toString();
        List<String> agentToolIds = agentToolRepo.findToolIdsByAgentId(agentId);
        if (!agentToolIds.isEmpty()) {
            return toolRepo.findAllById(agentToolIds).stream()
                    .filter(ToolDefinition::isEnabled)
                    .filter(this::isApproved)
                    .toList();
        }
        String role = agent.getRole() != null ? agent.getRole() : "WORKER";
        List<String> templateToolIds = roleTemplateRepo.findDefaultToolIdsByRole(role);
        if (templateToolIds.isEmpty() && !"WORKER".equals(role)) {
            // Role is free-text description (e.g. "Researches topics...") — fallback to WORKER template
            log.info("Role '{}' has no tool template; falling back to WORKER for agent {}", role, agentId);
            templateToolIds = roleTemplateRepo.findDefaultToolIdsByRole("WORKER");
        }
        if (!templateToolIds.isEmpty()) {
            return toolRepo.findAllById(templateToolIds).stream()
                    .filter(ToolDefinition::isEnabled)
                    .filter(this::isApproved)
                    .toList();
        }
        log.warn("No tools resolved for agent {} (role: {})", agentId, role);
        return List.of();
    }

    /** Layer A: only APPROVED tools (or legacy tools with null status) are resolvable. */
    private boolean isApproved(ToolDefinition tool) {
        return tool.getStatus() == null || tool.getStatus() == VersionStatus.APPROVED;
    }

    /** Check if agent has explicit tool assignments (UI-controlled) vs role-template defaults. */
    public boolean hasExplicitTools(Agent agent) {
        return !agentToolRepo.findToolIdsByAgentId(agent.getId().toString()).isEmpty();
    }
}
