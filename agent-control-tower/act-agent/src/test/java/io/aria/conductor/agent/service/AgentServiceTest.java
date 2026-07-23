package io.aria.conductor.agent.service;

import io.aria.conductor.agent.dto.RoleDefaultsResponse;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentSkillId;
import io.aria.conductor.common.model.SkillContext;
import io.aria.conductor.common.model.ToolDefinition;
import io.aria.conductor.common.repository.AgentSkillRepository;
import io.aria.conductor.common.repository.AgentToolRepository;
import io.aria.conductor.common.repository.RoleSkillTemplateRepository;
import io.aria.conductor.common.repository.RoleToolTemplateRepository;
import io.aria.conductor.common.repository.ToolDefinitionRepository;
import io.aria.conductor.common.service.SkillContextProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentServiceTest {

    @Mock AgentRepository agentRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock ObjectMapper objectMapper;
    @Mock AgentToolRepository agentToolRepository;
    @Mock ToolDefinitionRepository toolDefinitionRepository;
    @Mock SkillContextProvider skillProvider;
    @Mock AgentSkillRepository agentSkillRepository;
    @Mock RoleToolTemplateRepository roleToolTemplateRepository;
    @Mock RoleSkillTemplateRepository roleSkillTemplateRepository;
    @InjectMocks AgentService service;

    private Agent agentWith(UUID id) {
        return Agent.builder().id(id).role("dev").build();
    }

    @Test
    void assignSkillRejectsWhenNotEnabledSkill() {
        UUID agentId = UUID.randomUUID();
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agentWith(agentId)));
        when(skillProvider.getEnabledSkillsByIds(List.of("s1"))).thenReturn(List.of());

        assertThatThrownBy(() -> service.assignSkill(agentId, "s1"))
                .isInstanceOf(IllegalStateException.class);
        verify(agentSkillRepository, never()).save(any());
    }

    @Test
    void assignSkillSavesWhenValid() {
        UUID agentId = UUID.randomUUID();
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agentWith(agentId)));
        when(skillProvider.getEnabledSkillsByIds(List.of("s1")))
                .thenReturn(List.of(new SkillContext("s1", "n", "d", "t", "SKILL")));
        when(agentSkillRepository.existsById(any(AgentSkillId.class))).thenReturn(false);

        service.assignSkill(agentId, "s1");

        verify(agentSkillRepository).save(any());
    }

    @Test
    void setSkillsRejectsWhenAnyIdInvalidAndDoesNotMutate() {
        UUID agentId = UUID.randomUUID();
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agentWith(agentId)));
        // Two ids requested but only one resolves to an enabled SKILL — must abort before delete.
        when(skillProvider.getEnabledSkillsByIds(List.of("s1", "s2")))
                .thenReturn(List.of(new SkillContext("s1", "n", "d", "t", "SKILL")));

        assertThatThrownBy(() -> service.setSkills(agentId, List.of("s1", "s2")))
                .isInstanceOf(IllegalStateException.class);
        verify(agentSkillRepository, never()).deleteByAgentId(any());
        verify(agentSkillRepository, never()).save(any());
    }

    @Test
    void getRoleDefaultsReturnsEnabledToolsAndSkills() {
        when(roleToolTemplateRepository.findDefaultToolIdsByRole("dev")).thenReturn(List.of("t1"));
        when(toolDefinitionRepository.findAllById(List.of("t1"))).thenReturn(List.of(
                ToolDefinition.builder().id("t1").name("read_file").enabled(true).build()));
        when(roleSkillTemplateRepository.findDefaultSkillIdsByRole("dev")).thenReturn(List.of());
        when(skillProvider.getEnabledSkillsByIds(List.of())).thenReturn(List.of());

        RoleDefaultsResponse defaults = service.getRoleDefaults("dev");

        assertThat(defaults.tools()).hasSize(1);
        assertThat(defaults.tools().get(0).getName()).isEqualTo("read_file");
        assertThat(defaults.skills()).isEmpty();
    }
}
