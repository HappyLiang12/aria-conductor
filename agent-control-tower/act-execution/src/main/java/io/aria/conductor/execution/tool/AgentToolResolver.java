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
        // Free-text role descriptions (e.g. "Developer who fixes bugs") won't match a template key
        // exactly; map them to the canonical worker role by keyword so delegated workers still
        // receive their intended tool set (e.g. the git pack for dev) (#25).
        if (templateToolIds.isEmpty() && !"WORKER".equals(role)) {
            String keywordRole = matchKeywordRole(role);
            if (keywordRole != null) {
                templateToolIds = roleTemplateRepo.findDefaultToolIdsByRole(keywordRole);
                if (!templateToolIds.isEmpty()) {
                    log.info("Role '{}' matched '{}' template by keyword for agent {}", role, keywordRole, agentId);
                }
            }
        }
        if (templateToolIds.isEmpty() && !"WORKER".equals(role)) {
            // Still no match — fallback to the generic WORKER template
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

    /** True when the agent has explicit tool grants (which override role-template defaults). */
    public boolean hasExplicitTools(Agent agent) {
        return !agentToolRepo.findToolIdsByAgentId(agent.getId().toString()).isEmpty();
    }

    /** Map a free-text role description to a canonical worker role key by keyword (#25). */
    private String matchKeywordRole(String role) {
        String lower = role.toLowerCase();
        if (lower.contains("dev")) return "dev";
        if (lower.contains("qa") || lower.contains("tester")) return "qa";
        if (lower.contains("ba") || lower.contains("analyst")) return "ba";
        return null;
    }

    /** Layer A: only APPROVED tools (or legacy tools with null status) are resolvable. */
    private boolean isApproved(ToolDefinition tool) {
        return tool.getStatus() == null || tool.getStatus() == VersionStatus.APPROVED;
    }
}
