package io.aria.conductor.execution.tool;

import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.SkillContext;
import io.aria.conductor.common.repository.AgentSkillRepository;
import io.aria.conductor.common.repository.RoleSkillTemplateRepository;
import io.aria.conductor.common.service.SkillContextProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Resolves the enabled SKILL-stage skills an agent should receive at runtime.
 *
 * <p>Mirrors {@link AgentToolResolver}: agent-specific assignments first, then
 * the role's default template, then a generic {@code WORKER} fallback. Skills
 * are returned as {@link SkillContext} DTOs projected through the act-common
 * seam ({@link SkillContextProvider}) because act-execution cannot reference
 * {@code SkillDefinition}, which lives in act-knowledge.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentSkillResolver {
    private final AgentSkillRepository agentSkillRepo;
    private final RoleSkillTemplateRepository roleSkillTemplateRepo;
    private final SkillContextProvider skillProvider;

    public List<SkillContext> resolveForAgent(Agent agent) {
        String agentId = agent.getId().toString();
        List<String> agentSkillIds = agentSkillRepo.findSkillIdsByAgentId(agentId);
        if (!agentSkillIds.isEmpty()) {
            return skillProvider.getEnabledSkillsByIds(agentSkillIds);
        }
        String role = agent.getRole() != null ? agent.getRole() : "WORKER";
        List<String> templateSkillIds = roleSkillTemplateRepo.findDefaultSkillIdsByRole(role);
        if (templateSkillIds.isEmpty() && !"WORKER".equals(role)) {
            // Fall back to the generic WORKER default set for roles without a
            // specific template, before giving up.
            templateSkillIds = roleSkillTemplateRepo.findDefaultSkillIdsByRole("WORKER");
        }
        if (!templateSkillIds.isEmpty()) {
            return skillProvider.getEnabledSkillsByIds(templateSkillIds);
        }
        return List.of();
    }
}
