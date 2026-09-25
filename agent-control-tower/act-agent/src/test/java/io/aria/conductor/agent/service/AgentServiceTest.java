package io.aria.conductor.agent.service;

import io.aria.conductor.agent.dto.AgentResponse;
import io.aria.conductor.agent.dto.CreateAgentRequest;
import io.aria.conductor.agent.dto.RoleDefaultsResponse;
import io.aria.conductor.agent.dto.UpdateAgentRequest;
import io.aria.conductor.agent.eligibility.AgentPickupEligibility;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentSkillId;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.SkillContext;
import io.aria.conductor.common.model.ToolDefinition;
import io.aria.conductor.common.repository.AgentSkillRepository;
import io.aria.conductor.common.repository.AgentToolRepository;
import io.aria.conductor.common.repository.RoleSkillTemplateRepository;
import io.aria.conductor.common.repository.RoleToolTemplateRepository;
import io.aria.conductor.common.repository.ToolDefinitionRepository;
import io.aria.conductor.common.runtime.AgentExecutionPolicy;
import io.aria.conductor.common.runtime.AgentExecutionSettings;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.runtime.WorkspaceMode;
import io.aria.conductor.common.service.SkillContextProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    @Mock AgentExecutionPolicy executionPolicy;
    @Spy AgentPickupEligibility eligibility = new AgentPickupEligibility();
    @InjectMocks AgentService service;

    private final UUID agentId = UUID.randomUUID();

    private Agent agentWith(UUID id) {
        return Agent.builder().id(id).role("dev").build();
    }

    private Agent existingWorker() {
        return Agent.builder().id(agentId).name("worker").role("dev")
                .agentType(AgentType.NATIVE).healthStatus(HealthStatus.HEALTHY)
                .pickupEnabled(Boolean.TRUE).build();
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

    @Test
    void responseReportsEligibilityAndReasons() {
        Agent aria = Agent.builder().id(AriaConstants.ARIA_AGENT_ID).name("Aria")
                .agentType(AgentType.NATIVE).healthStatus(HealthStatus.HEALTHY)
                .pickupEnabled(Boolean.TRUE).build();

        AgentResponse response = service.toResponse(aria);

        assertThat(response.isPickupEligible()).isFalse();
        assertThat(response.getPickupIneligibleReasons()).containsExactly("RESERVED_OPERATOR_AGENT");
    }

    @Test
    void updateAppliesPickupEnabled() {
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(existingWorker()));
        when(agentRepository.save(any(Agent.class))).thenAnswer(inv -> inv.getArgument(0));

        AgentResponse response = service.updateAgent(agentId, UpdateAgentRequest.builder()
                .pickupEnabled(Boolean.FALSE).build());

        assertThat(response.isPickupEligible()).isFalse();
        assertThat(response.getPickupIneligibleReasons()).containsExactly("PICKUP_DISABLED");
    }

    @Test
    void updateLeavesPickupEnabledUntouchedWhenNull() {
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(existingWorker()));
        when(agentRepository.save(any(Agent.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateAgent(agentId, UpdateAgentRequest.builder().name("renamed").build());

        verify(agentRepository).save(argThat(a -> Boolean.TRUE.equals(a.getPickupEnabled())));
    }

    // ---- Task 18 fix round 1: the write path runs the shared admission policy ----

    @Test
    void createAgent_refusesASelectionAdmissionRefuses() {
        when(executionPolicy.normalize(any())).thenThrow(
                new IllegalArgumentException("Unsupported agent core: langchain"));

        assertThatThrownBy(() -> service.createAgent(CreateAgentRequest.builder()
                .name("legacy").agentType(AgentType.NATIVE).adkProvider("langchain").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported agent core: langchain");
        verify(agentRepository, never()).save(any());
    }

    @Test
    void createAgent_refusesAContradictoryWorkspaceSelection() {
        when(executionPolicy.normalize(any())).thenThrow(
                new IllegalArgumentException("Direct workspace requires an explicitly selected directory"));

        assertThatThrownBy(() -> service.createAgent(CreateAgentRequest.builder()
                .name("direct-without-path").agentType(AgentType.NATIVE).adkProvider("opencode")
                .workspaceMode(WorkspaceMode.DIRECT).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Direct workspace requires an explicitly selected directory");
        verify(agentRepository, never()).save(any());
    }

    @Test
    void createAgent_validatesTheSelectionAndStoresTheFieldsAsSupplied() {
        when(agentRepository.save(any(Agent.class))).thenAnswer(inv -> {
            Agent agent = inv.getArgument(0);
            agent.setId(agentId);
            return agent;
        });
        when(executionPolicy.normalize(any())).thenReturn(new AgentExecutionSettings(
                "qoder", ExecutionMode.HOST, WorkspaceMode.WORKTREE, "C:/repo", "main"));

        AgentResponse response = service.createAgent(CreateAgentRequest.builder()
                .name("worker").agentType(AgentType.NATIVE).adkProvider("qoder")
                .executionMode(ExecutionMode.HOST).workspaceMode(WorkspaceMode.WORKTREE)
                .workspacePath("C:/repo").workspaceBaseRef("main").build());

        ArgumentCaptor<AgentExecutionSettings> settings =
                ArgumentCaptor.forClass(AgentExecutionSettings.class);
        verify(executionPolicy).normalize(settings.capture());
        assertThat(settings.getValue().coreId()).isEqualTo("qoder");
        assertThat(settings.getValue().executionMode()).isEqualTo(ExecutionMode.HOST);
        assertThat(settings.getValue().workspaceMode()).isEqualTo(WorkspaceMode.WORKTREE);
        assertThat(settings.getValue().workspacePath()).isEqualTo("C:/repo");
        assertThat(settings.getValue().workspaceBaseRef()).isEqualTo("main");
        // persisted exactly as supplied (validation adds a refusal, never a rewrite)
        ArgumentCaptor<Agent> saved = ArgumentCaptor.forClass(Agent.class);
        verify(agentRepository).save(saved.capture());
        assertThat(saved.getValue().getAdkProvider()).isEqualTo("qoder");
        assertThat(saved.getValue().getExecutionMode()).isEqualTo(ExecutionMode.HOST);
        assertThat(saved.getValue().getWorkspaceMode()).isEqualTo(WorkspaceMode.WORKTREE);
        assertThat(saved.getValue().getWorkspacePath()).isEqualTo("C:/repo");
        assertThat(saved.getValue().getWorkspaceBaseRef()).isEqualTo("main");
        assertThat(response.getAdkProvider()).isEqualTo("qoder");
    }

    @Test
    void updateAgent_refusesASelectionChangeAdmissionRefuses_andPersistsNothing() {
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(existingWorker()));
        when(executionPolicy.normalize(any())).thenThrow(
                new IllegalArgumentException("Unsupported execution mode: opencode/SANDBOX"));

        assertThatThrownBy(() -> service.updateAgent(agentId, UpdateAgentRequest.builder()
                .executionMode(ExecutionMode.SANDBOX).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported execution mode: opencode/SANDBOX");
        verify(agentRepository, never()).save(any());
    }

    @Test
    void updateAgent_renameOnly_leavesALegacySelectionUntouched() {
        // A request that touches no selection field must not run admission: otherwise a
        // legacy row stored on a removed core could not even be renamed before retirement.
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(existingWorker()));
        when(agentRepository.save(any(Agent.class))).thenAnswer(inv -> inv.getArgument(0));

        AgentResponse response = service.updateAgent(agentId, UpdateAgentRequest.builder()
                .name("renamed").build());

        assertThat(response.getName()).isEqualTo("renamed");
        verifyNoInteractions(executionPolicy);
    }
}
