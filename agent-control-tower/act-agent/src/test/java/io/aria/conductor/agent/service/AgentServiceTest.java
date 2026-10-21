package io.aria.conductor.agent.service;

import io.aria.conductor.agent.dto.AgentResponse;
import io.aria.conductor.agent.dto.ApplyRuntimeResult;
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
import io.aria.conductor.common.repository.AgentSkillRepository;
import io.aria.conductor.common.repository.RoleSkillTemplateRepository;
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
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentServiceTest {

    @Mock AgentRepository agentRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock ObjectMapper objectMapper;
    @Mock SkillContextProvider skillProvider;
    @Mock AgentSkillRepository agentSkillRepository;
    @Mock RoleSkillTemplateRepository roleSkillTemplateRepository;
    @Mock AgentExecutionPolicy executionPolicy;
    @Spy AgentPickupEligibility eligibility = new AgentPickupEligibility();
    @InjectMocks AgentService service;

    // The @Value switch defaults to false under plain Mockito injection; the
    // fleet contract is what these pins test, so every test runs enforcement ON
    // except the one pinning the harness kill-switch.
    @org.junit.jupiter.api.BeforeEach
    void enforcementOn() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "enforceUniformity", Boolean.TRUE);
    }

    private final UUID agentId = UUID.randomUUID();

    private Agent agentWith(UUID id) {
        return Agent.builder().id(id).role("dev").build();
    }

    private Agent existingWorker() {
        return Agent.builder().id(agentId).name("worker").role("dev")
                .agentType(AgentType.ADK).healthStatus(HealthStatus.HEALTHY)
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
    void getRoleDefaultsReturnsEnabledSkills() {
        when(roleSkillTemplateRepository.findDefaultSkillIdsByRole("dev")).thenReturn(List.of("s1"));
        when(skillProvider.getEnabledSkillsByIds(List.of("s1")))
                .thenReturn(List.of(new SkillContext("s1", "triage", "d", "t", "SKILL")));

        RoleDefaultsResponse defaults = service.getRoleDefaults("dev");

        assertThat(defaults.skills()).hasSize(1);
        assertThat(defaults.skills().get(0).id()).isEqualTo("s1");
    }

    @Test
    void responseReportsEligibilityAndReasons() {
        Agent aria = Agent.builder().id(AriaConstants.ARIA_AGENT_ID).name("Aria")
                .agentType(AgentType.ADK).healthStatus(HealthStatus.HEALTHY)
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
                .name("legacy").agentType(AgentType.ADK).adkProvider("langchain").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported agent core: langchain");
        verify(agentRepository, never()).save(any());
    }

    @Test
    void createAgent_refusesAContradictoryWorkspaceSelection() {
        when(executionPolicy.normalize(any())).thenThrow(
                new IllegalArgumentException("Direct workspace requires an explicitly selected directory"));

        assertThatThrownBy(() -> service.createAgent(CreateAgentRequest.builder()
                .name("direct-without-path").agentType(AgentType.ADK).adkProvider("opencode")
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
                .name("worker").agentType(AgentType.ADK).adkProvider("qoder")
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

    // ---- Fleet runtime governance (2026-10-11): the fleet runs ONE (core, mode) pair ----

    private Agent fleetAgent(String adkProvider, ExecutionMode executionMode) {
        return Agent.builder().id(UUID.randomUUID()).name("worker-" + adkProvider)
                .agentType(AgentType.ADK).adkProvider(adkProvider).executionMode(executionMode)
                .healthStatus(HealthStatus.HEALTHY).pickupEnabled(Boolean.TRUE).build();
    }

    private CreateAgentRequest aCreateRequest(String adkProvider, ExecutionMode executionMode) {
        return CreateAgentRequest.builder().name("new-worker").agentType(AgentType.ADK)
                .adkProvider(adkProvider).executionMode(executionMode).build();
    }

    private UpdateAgentRequest anUpdateRequest(String adkProvider, ExecutionMode executionMode) {
        return UpdateAgentRequest.builder().adkProvider(adkProvider)
                .executionMode(executionMode).build();
    }

    @Test
    void creatingAnAgentWithTheFleetRuntimeIsAllowed() {
        // uniform fleet: opencode/SANDBOX; the write carries the same pair
        when(agentRepository.findAll()).thenReturn(List.of(fleetAgent("opencode", ExecutionMode.SANDBOX)));
        when(agentRepository.save(any(Agent.class))).thenAnswer(inv -> inv.getArgument(0));

        AgentResponse response = service.createAgent(aCreateRequest("opencode", ExecutionMode.SANDBOX));

        assertThat(response.getAdkProvider()).isEqualTo("opencode");
        assertThat(response.getExecutionMode()).isEqualTo(ExecutionMode.SANDBOX);
    }

    @Test
    void creatingAnAgentOnAnEmptyFleetIsAllowed() {
        // a brand-new fleet (zero agents) allows any first core/mode
        when(agentRepository.findAll()).thenReturn(List.of());
        when(agentRepository.save(any(Agent.class))).thenAnswer(inv -> inv.getArgument(0));

        AgentResponse response = service.createAgent(aCreateRequest("qoder", ExecutionMode.HOST));

        assertThat(response.getAdkProvider()).isEqualTo("qoder");
    }

    @Test
    void enforcementSwitchOffAllowsTheMixedSeedTheE2EHarnessNeeds() {
        // The deterministic core E2E harness runs with
        // aria.runtime.enforce-uniformity=false: it exercises BOTH cores side by
        // side, which the production uniformity contract forbids.
        org.springframework.test.util.ReflectionTestUtils.setField(service, "enforceUniformity", Boolean.FALSE);
        // The short-circuited guard never reads the fleet; lenient keeps the
        // strict-stubs check honest about that.
        lenient().when(agentRepository.findAll()).thenReturn(List.of(fleetAgent("opencode", ExecutionMode.SANDBOX)));
        when(agentRepository.save(any(Agent.class))).thenAnswer(inv -> inv.getArgument(0));

        AgentResponse response = service.createAgent(aCreateRequest("qoder", ExecutionMode.HOST));

        assertThat(response.getAdkProvider()).isEqualTo("qoder");
        assertThat(response.getExecutionMode()).isEqualTo(ExecutionMode.HOST);
    }

    @Test
    void secondDefaultCreateOnAFleetOfDefaultCreatedAgentsIsAllowed() {
        // A default create stores the row with a NULL execution mode (the selection is
        // persisted as supplied; admission applies the documented defaults later). The
        // guard compares EFFECTIVE pairs on both sides, so the next default create on
        // such a fleet must not be refused as an off-fleet write.
        when(agentRepository.findAll()).thenReturn(List.of(
                Agent.builder().id(UUID.randomUUID()).name("seed").agentType(AgentType.ADK)
                        .adkProvider("opencode").healthStatus(HealthStatus.HEALTHY).build()));
        when(agentRepository.save(any(Agent.class))).thenAnswer(inv -> inv.getArgument(0));

        AgentResponse response = service.createAgent(CreateAgentRequest.builder()
                .name("second-default").agentType(AgentType.ADK).build());

        assertThat(response.getAdkProvider()).isEqualTo("opencode");
    }

    @Test
    void creatingAnAgentOffTheFleetRuntimeIsRejected() {
        when(agentRepository.findAll()).thenReturn(List.of(fleetAgent("opencode", ExecutionMode.SANDBOX)));

        assertThatThrownBy(() -> service.createAgent(aCreateRequest("qoder", ExecutionMode.HOST)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Runtime mixing is not allowed")
                .hasMessageContaining("PUT /api/v1/agents/runtime");
        verify(agentRepository, never()).save(any());
    }

    @Test
    void updatingAnAgentIntoAMixedFleetIsRejected() {
        when(agentRepository.findById(agentId))
                .thenReturn(Optional.of(fleetAgent("opencode", ExecutionMode.SANDBOX)));
        when(agentRepository.findAll()).thenReturn(List.of(
                fleetAgent("opencode", ExecutionMode.SANDBOX),
                fleetAgent("qoder", ExecutionMode.HOST))); // fleet already mixed

        assertThatThrownBy(() -> service.updateAgent(agentId, anUpdateRequest("opencode", ExecutionMode.HOST)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("The fleet runtime is mixed");
        verify(agentRepository, never()).save(any());
    }

    @Test
    void updatingAnAgentOnAUniformFleetToTheFleetRuntimeIsAllowed() {
        when(agentRepository.findById(agentId))
                .thenReturn(Optional.of(fleetAgent("opencode", ExecutionMode.SANDBOX)));
        when(agentRepository.findAll()).thenReturn(List.of(fleetAgent("opencode", ExecutionMode.SANDBOX)));
        when(agentRepository.save(any(Agent.class))).thenAnswer(inv -> inv.getArgument(0));

        AgentResponse response = service.updateAgent(agentId, anUpdateRequest("opencode", ExecutionMode.SANDBOX));

        assertThat(response.getExecutionMode()).isEqualTo(ExecutionMode.SANDBOX);
    }

    @Test
    void nameOnlyUpdateOnAUniformFleetKeepsTheCurrentRuntimeAndPasses() {
        // Partial update: null adkProvider/executionMode resolve to the agent's CURRENT
        // values before the guard runs, so an unrelated edit never introduces a mix.
        when(agentRepository.findById(agentId))
                .thenReturn(Optional.of(fleetAgent("opencode", ExecutionMode.SANDBOX)));
        when(agentRepository.findAll()).thenReturn(List.of(fleetAgent("opencode", ExecutionMode.SANDBOX)));
        when(agentRepository.save(any(Agent.class))).thenAnswer(inv -> inv.getArgument(0));

        AgentResponse response = service.updateAgent(agentId, UpdateAgentRequest.builder()
                .name("renamed").build());

        assertThat(response.getName()).isEqualTo("renamed");
    }

    @Test
    void retiringAnAgentNeverTriggersTheGuard() {
        when(agentRepository.findById(agentId))
                .thenReturn(Optional.of(fleetAgent("opencode", ExecutionMode.SANDBOX)));
        when(agentRepository.save(any(Agent.class))).thenAnswer(inv -> inv.getArgument(0));

        AgentResponse response = service.retireAgent(agentId); // retirement is exempt per spec

        assertThat(response.getHealthStatus()).isEqualTo(HealthStatus.RETIRED);
        verify(agentRepository, never()).findAll(); // the uniformity guard never ran
    }

    // ---- Task 2 (2026-10-11): PUT /api/v1/agents/runtime — the converging batch action ----

    @Test
    void applyRuntimeUpdatesEveryNonRetiredAgentInOneCall() {
        Agent live1 = fleetAgent("opencode", ExecutionMode.SANDBOX);
        Agent live2 = fleetAgent("qoder", ExecutionMode.HOST);
        Agent retired = fleetAgent("opencode", ExecutionMode.SANDBOX);
        retired.setName("retired-worker"); // distinct name for the batch-membership assertion
        retired.setHealthStatus(HealthStatus.RETIRED);
        when(agentRepository.findAll()).thenReturn(List.of(live1, live2, retired));
        when(executionPolicy.normalize(any())).thenAnswer(inv -> inv.getArgument(0));
        when(agentRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        ApplyRuntimeResult result = service.applyRuntimeToAll("qoder", ExecutionMode.HOST);

        assertThat(result.adkProvider()).isEqualTo("qoder");
        assertThat(result.executionMode()).isEqualTo(ExecutionMode.HOST);
        assertThat(result.updated()).isEqualTo(2); // retired agent untouched
        // exactly the two live agents were persisted, both converged to qoder/HOST
        verify(agentRepository).saveAll(argThat((Iterable<Agent> batch) -> {
            List<Agent> rows = StreamSupport.stream(batch.spliterator(), false).toList();
            return rows.size() == 2
                    && rows.stream().noneMatch(a -> "retired-worker".equals(a.getName()))
                    && rows.stream().allMatch(a -> "qoder".equals(a.getAdkProvider())
                            && a.getExecutionMode() == ExecutionMode.HOST);
        }));
        assertThat(live1.getAdkProvider()).isEqualTo("qoder");
        assertThat(live1.getExecutionMode()).isEqualTo(ExecutionMode.HOST);
        assertThat(live2.getAdkProvider()).isEqualTo("qoder");
        assertThat(live2.getExecutionMode()).isEqualTo(ExecutionMode.HOST);
        // the retired row is neither mutated nor counted
        assertThat(retired.getAdkProvider()).isEqualTo("opencode");
        assertThat(retired.getExecutionMode()).isEqualTo(ExecutionMode.SANDBOX);
    }

    @Test
    void applyRuntimeRejectsAnUndeclaredCore() {
        when(executionPolicy.normalize(any()))
                .thenThrow(new IllegalArgumentException("Unsupported agent core: nonexistent"));

        assertThatThrownBy(() -> service.applyRuntimeToAll("nonexistent", ExecutionMode.HOST))
                .isInstanceOf(IllegalArgumentException.class) // surfaces as 400
                .hasMessageContaining("Unsupported agent core");
        // inventory validation runs BEFORE any fleet read or mutation
        verify(agentRepository, never()).findAll();
        verify(agentRepository, never()).saveAll(any());
    }

    @Test
    void applyRuntimeRejectsAnUnsupportedModeOnADeclaredCore() {
        when(executionPolicy.normalize(any()))
                .thenThrow(new IllegalArgumentException("Unsupported execution mode: opencode/HOST"));

        assertThatThrownBy(() -> service.applyRuntimeToAll("opencode", ExecutionMode.HOST))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported execution mode");
        verify(agentRepository, never()).findAll();
        verify(agentRepository, never()).saveAll(any());
    }
}
