package io.aria.conductor.aria.init;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.LlmProviderRepository;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentToolId;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.ToolDefinition;
import io.aria.conductor.common.repository.AgentToolRepository;
import io.aria.conductor.common.repository.ToolDefinitionRepository;
import io.aria.conductor.execution.maintenance.LegacySetupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Startup behavior of {@link AriaDefaultAgentInitializer} after the Task 18
 * cutover: the Aria row is created through {@link LegacySetupService} on the
 * supported {@code opencode}+{@code SANDBOX} built-in, an existing row is never
 * rewritten, orchestration-tool assignment/pruning is unchanged, a step failure
 * degrades instead of killing the boot, and no provider is ever pre-warmed
 * (the permanent ADK pre-warm and its DEGRADED reconciler are gone with the
 * LangChain runtime).
 */
@ExtendWith(MockitoExtension.class)
class AriaDefaultAgentInitializerTest {

    @Mock AgentRepository agentRepository;
    @Mock ToolDefinitionRepository toolDefinitionRepository;
    @Mock AgentToolRepository agentToolRepository;
    @Mock LlmProviderRepository llmProviderRepository;
    @Mock LegacySetupService legacySetupService;
    @Mock ApplicationArguments args;

    private Agent ariaAgent;

    @BeforeEach
    void setUp() {
        ariaAgent = Agent.builder()
                .id(AriaConstants.ARIA_AGENT_ID)
                .name("Aria")
                .agentType(AgentType.NATIVE)
                .adkProvider(LegacySetupService.BUILTIN_CORE)
                .executionMode(LegacySetupService.BUILTIN_MODE)
                .config("{\"maxToolCallRounds\":15}")
                .healthStatus(HealthStatus.HEALTHY)
                .build();
    }

    @Test
    void createsAriaThroughLegacySetupService_whenMissing() {
        when(legacySetupService.initializeMissingAria(any())).thenReturn(ariaAgent);
        when(toolDefinitionRepository.findAllApprovedAndEnabled()).thenReturn(java.util.List.of());
        when(llmProviderRepository.findByActiveTrue()).thenReturn(Optional.of(
                io.aria.conductor.common.model.LlmProvider.builder().name("p").active(true).build()));

        new AriaDefaultAgentInitializer(agentRepository, toolDefinitionRepository,
                agentToolRepository, llmProviderRepository, legacySetupService).run(args);

        ArgumentCaptor<String> config = ArgumentCaptor.forClass(String.class);
        verify(legacySetupService).initializeMissingAria(config.capture());
        assertThat(config.getValue())
                .contains("\"taskApprovalRequired\":false")
                .contains("\"maxToolCallRounds\":15")
                .contains("You are Aria");
        // the initializer never writes the agent row itself: creation belongs to the
        // create-only setup service
        verify(agentRepository, never()).save(any(Agent.class));
    }

    @Test
    void neverPreWarmsOrStampsHealth() {
        when(legacySetupService.initializeMissingAria(any())).thenReturn(ariaAgent);
        when(toolDefinitionRepository.findAllApprovedAndEnabled()).thenReturn(java.util.List.of());
        when(llmProviderRepository.findByActiveTrue()).thenReturn(Optional.of(
                io.aria.conductor.common.model.LlmProvider.builder().name("p").active(true).build()));

        new AriaDefaultAgentInitializer(agentRepository, toolDefinitionRepository,
                agentToolRepository, llmProviderRepository, legacySetupService).run(args);

        // no health stamp, no pre-warm, no DEGRADED write
        verify(agentRepository, never()).save(any(Agent.class));
    }

    @Test
    void permanentPreWarmAndItsReconcilerAreGone() {
        // The Task 18 removal assertion: no permanent pre-warm seam and no DEGRADED
        // recovery reconciler survives from the LangChain era.
        assertThatThrownBy(() -> AriaDefaultAgentInitializer.class.getMethod("recoverDegradedAria"))
                .isInstanceOf(NoSuchMethodException.class);
    }

    @Test
    void assignsOnlyOrchestrationTools_andPrunesOthers() {
        // #25: Aria must receive only orchestration tools (e.g. run_agent) and any previously-granted
        // non-orchestration tool (e.g. git_push) must be pruned at startup.
        ToolDefinition runAgent = ToolDefinition.builder().id("tool-run_agent").name("run_agent").enabled(true).build();
        ToolDefinition gitPush = ToolDefinition.builder().id("tool-git_push").name("git_push").enabled(true).build();
        when(toolDefinitionRepository.findAllApprovedAndEnabled()).thenReturn(java.util.List.of(runAgent, gitPush));
        when(llmProviderRepository.findByActiveTrue()).thenReturn(Optional.of(
                io.aria.conductor.common.model.LlmProvider.builder().name("p").active(true).build()));
        // Aria currently holds git_push (to be pruned) but not run_agent (to be added).
        when(agentToolRepository.findToolIdsByAgentId(AriaConstants.ARIA_AGENT_ID.toString()))
                .thenReturn(java.util.List.of("tool-git_push"));
        when(agentToolRepository.existsById(new AgentToolId(AriaConstants.ARIA_AGENT_ID.toString(), "tool-run_agent")))
                .thenReturn(false);

        new AriaDefaultAgentInitializer(agentRepository, toolDefinitionRepository,
                agentToolRepository, llmProviderRepository, legacySetupService).run(args);

        verify(agentToolRepository).save(any()); // run_agent assigned
        verify(agentToolRepository).deleteById(new AgentToolId(AriaConstants.ARIA_AGENT_ID.toString(), "tool-git_push"));
    }

    @Test
    void stepFailureDuringInitialization_degradesAndBootsInsteadOfAborting() {
        // Boot hardening: the initializer runs in the HIGHEST_PRECEDENCE ApplicationRunner —
        // an exception there must be caught (ERROR logged) and startup must continue;
        // every step is idempotent and retried on the next boot.
        when(legacySetupService.initializeMissingAria(any()))
                .thenThrow(new RuntimeException("db hiccup"));

        AriaDefaultAgentInitializer initializer = new AriaDefaultAgentInitializer(agentRepository,
                toolDefinitionRepository, agentToolRepository, llmProviderRepository, legacySetupService);

        // no exception escapes
        assertThatCode(() -> initializer.run(args)).doesNotThrowAnyException();
    }

    @Test
    void toolAssignmentFailureIsCaught() {
        when(toolDefinitionRepository.findAllApprovedAndEnabled()).thenThrow(new RuntimeException("db hiccup"));
        when(legacySetupService.initializeMissingAria(any())).thenReturn(ariaAgent);
        when(llmProviderRepository.findByActiveTrue()).thenReturn(Optional.of(
                io.aria.conductor.common.model.LlmProvider.builder().name("p").active(true).build()));

        new AriaDefaultAgentInitializer(agentRepository, toolDefinitionRepository,
                agentToolRepository, llmProviderRepository, legacySetupService).run(args);

        verify(agentToolRepository, never()).save(any());
    }
}
