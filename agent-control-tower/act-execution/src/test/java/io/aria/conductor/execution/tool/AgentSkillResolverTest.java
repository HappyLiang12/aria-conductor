package io.aria.conductor.execution.tool;

import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.SkillContext;
import io.aria.conductor.common.repository.AgentSkillRepository;
import io.aria.conductor.common.repository.RoleSkillTemplateRepository;
import io.aria.conductor.common.service.SkillContextProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentSkillResolverTest {
    @Mock AgentSkillRepository agentSkillRepo;
    @Mock RoleSkillTemplateRepository roleSkillTemplateRepo;
    @Mock SkillContextProvider skillProvider;
    @InjectMocks AgentSkillResolver resolver;

    private Agent agent(String role) {
        return Agent.builder().id(UUID.randomUUID()).role(role).build();
    }

    @Test
    void shouldReturnAgentAssignedSkills() {
        Agent agent = agent("dev");
        when(agentSkillRepo.findSkillIdsByAgentId(agent.getId().toString())).thenReturn(List.of("s1"));
        when(skillProvider.getEnabledSkillsByIds(List.of("s1")))
                .thenReturn(List.of(new SkillContext("s1", "Spec Writer", "d", "t", "SKILL")));

        List<SkillContext> skills = resolver.resolveForAgent(agent);

        assertThat(skills).hasSize(1);
        assertThat(skills.get(0).name()).isEqualTo("Spec Writer");
    }

    @Test
    void shouldFallbackToRoleTemplateWhenNoAgentAssignment() {
        Agent agent = agent("dev");
        when(agentSkillRepo.findSkillIdsByAgentId(agent.getId().toString())).thenReturn(List.of());
        when(roleSkillTemplateRepo.findDefaultSkillIdsByRole("dev")).thenReturn(List.of("s2"));
        when(skillProvider.getEnabledSkillsByIds(List.of("s2")))
                .thenReturn(List.of(new SkillContext("s2", "Reviewer", "d", "t", "SKILL")));

        List<SkillContext> skills = resolver.resolveForAgent(agent);

        assertThat(skills).hasSize(1);
        assertThat(skills.get(0).name()).isEqualTo("Reviewer");
    }

    @Test
    void shouldFallbackToWorkerWhenRoleSpecificEmpty() {
        Agent agent = agent("dev");
        when(agentSkillRepo.findSkillIdsByAgentId(agent.getId().toString())).thenReturn(List.of());
        when(roleSkillTemplateRepo.findDefaultSkillIdsByRole("dev")).thenReturn(List.of());
        when(roleSkillTemplateRepo.findDefaultSkillIdsByRole("WORKER")).thenReturn(List.of("s3"));
        when(skillProvider.getEnabledSkillsByIds(List.of("s3")))
                .thenReturn(List.of(new SkillContext("s3", "Generalist", "d", "t", "SKILL")));

        List<SkillContext> skills = resolver.resolveForAgent(agent);

        assertThat(skills).hasSize(1);
        assertThat(skills.get(0).name()).isEqualTo("Generalist");
    }

    @Test
    void shouldReturnEmptyWhenNothingResolves() {
        Agent agent = agent("dev");
        when(agentSkillRepo.findSkillIdsByAgentId(agent.getId().toString())).thenReturn(List.of());
        when(roleSkillTemplateRepo.findDefaultSkillIdsByRole("dev")).thenReturn(List.of());
        when(roleSkillTemplateRepo.findDefaultSkillIdsByRole("WORKER")).thenReturn(List.of());

        assertThat(resolver.resolveForAgent(agent)).isEmpty();
    }
}
