package io.aria.conductor.knowledge.service;

import io.aria.conductor.common.model.SkillContext;
import io.aria.conductor.common.repository.AgentSkillRepository;
import io.aria.conductor.common.service.SkillContextProvider;
import io.aria.conductor.knowledge.selfimprove.SkillDefinition;
import io.aria.conductor.knowledge.selfimprove.SkillDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SkillContextProviderImpl implements SkillContextProvider {

    private final AgentSkillRepository agentSkillRepository;
    private final SkillDefinitionRepository skillDefinitionRepository;

    @Override
    public List<SkillContext> getEnabledSkillsForAgent(String agentId) {
        return getEnabledSkillsByIds(agentSkillRepository.findSkillIdsByAgentId(agentId));
    }

    @Override
    public List<SkillContext> getEnabledSkillsByIds(Collection<String> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) {
            return List.of();
        }
        return skillDefinitionRepository.findAllById(skillIds).stream()
                .filter(s -> s.isEnabled() && "SKILL".equals(s.getStage()) && s.getTemplate() != null)
                .map(s -> new SkillContext(s.getId(), s.getName(), s.getDescription(), s.getTemplate(), s.getStage()))
                .sorted(Comparator.comparing(SkillContext::name, Comparator.nullsLast(String::compareTo)))
                .toList();
    }
}