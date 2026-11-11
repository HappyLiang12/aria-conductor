package io.aria.conductor.execution.context.contributor;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.common.context.ContextContributor;
import io.aria.conductor.common.context.ContextRequest;
import io.aria.conductor.common.context.ContextSection;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.common.model.SkillContext;
import io.aria.conductor.execution.tool.AgentSkillResolver;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The enabled SKILL-stage skills assigned to the agent (spec §8.1, "Skills"),
 * one line per skill. No skills means no section.
 */
@Component
public class SkillsContextContributor implements ContextContributor {

    private final AgentRepository agents;
    private final AgentSkillResolver resolver;

    public SkillsContextContributor(AgentRepository agents, AgentSkillResolver resolver) {
        this.agents = agents;
        this.resolver = resolver;
    }

    @Override
    public String id() {
        return "skills";
    }

    @Override
    public int order() {
        return 60;
    }

    @Override
    public boolean appliesTo(ContextRequest request) {
        return request.agentId() != null;
    }

    @Override
    public List<ContextSection> contribute(ContextRequest request) {
        Agent agent = agents.findById(request.agentId()).orElse(null);
        if (agent == null) {
            return List.of();
        }
        List<SkillContext> skills = resolver.resolveForAgent(agent);
        if (skills == null || skills.isEmpty()) {
            return List.of();
        }
        StringBuilder content = new StringBuilder();
        for (SkillContext skill : skills) {
            content.append("- **").append(skill.name()).append("**: ")
                    .append(skill.template()).append('\n');
        }
        List<String> sources = skills.stream().map(SkillContext::id).toList();
        return List.of(new ContextSection(id(), "Skills", content.toString(),
                Sensitivity.INTERNAL, sources, false, null));
    }
}
