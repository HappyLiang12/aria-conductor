package io.aria.conductor.aria.init;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.LlmProviderRepository;
import io.aria.conductor.aria.AriaConstants;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentToolId;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.ToolDefinition;
import io.aria.conductor.common.repository.AgentToolRepository;
import io.aria.conductor.common.repository.ToolDefinitionRepository;
import io.aria.conductor.execution.adk.AdkProvider;
import io.aria.conductor.execution.adk.AdkProviderRegistry;
import io.aria.conductor.execution.adk.AdkSystemProperties;
import io.aria.conductor.execution.adk.TaskExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.env.Environment;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AriaDefaultAgentInitializerTest {

    @Mock AgentRepository agentRepository;
    @Mock ToolDefinitionRepository toolDefinitionRepository;
    @Mock AgentToolRepository agentToolRepository;
    @Mock LlmProviderRepository llmProviderRepository;
    @Mock AdkProviderRegistry adkProviderRegistry;
    @Mock AdkProvider adkProvider;
    @Mock Environment environment;
    @Mock ApplicationArguments args;

    private Agent ariaAgent;

    @BeforeEach
    void setUp() {
        ariaAgent = Agent.builder()
                .id(AriaConstants.ARIA_AGENT_ID)
                .name("Aria")
                .role("AI operator assistant")
                .agentType(AgentType.NATIVE)
                .adkProvider("langchain")
                .config("{\"maxToolCallRounds\":15}")
                .healthStatus(HealthStatus.HEALTHY)
                .build();
        // The registry resolves whichever agent instance reaches the pre-warm
        // lenient: profile-skip and recovery no-op tests never reach the resolve
        lenient().when(adkProviderRegistry.resolve(any(Agent.class))).thenReturn(adkProvider);
    }

    @Test
    void preWarmsAdk_whenNotTestProfile() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});
        when(agentRepository.findById(AriaConstants.ARIA_AGENT_ID)).thenReturn(Optional.of(ariaAgent));
        when(toolDefinitionRepository.findAllApprovedAndEnabled()).thenReturn(java.util.List.of());
        when(llmProviderRepository.findByActiveTrue()).thenReturn(java.util.Optional.empty());

        new AriaDefaultAgentInitializer(agentRepository, toolDefinitionRepository,
                agentToolRepository, llmProviderRepository, adkProviderRegistry, environment, new AdkSystemProperties()).run(args);

        verify(adkProvider).prepareAgent(AriaConstants.ARIA_AGENT_ID, ariaAgent);
    }

    @Test
    void survivesPreWarmFailure_whenOpencodeProviderThrowsTaskExecutionException() {
        // Real failure mode (CI / local dev): OpenSandbox unreachable at pre-warm time makes
        // OpenCodeAdkProvider.prepareAgent throw TaskExecutionException(SANDBOX_UNAVAILABLE).
        // A transient pre-warm failure must NOT kill the JVM — the sandbox is created lazily
        // on first real use (executeTask -> getOrPrepareInstance -> prepareInstance).
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});
        when(agentRepository.findById(AriaConstants.ARIA_AGENT_ID)).thenReturn(Optional.of(ariaAgent));
        when(toolDefinitionRepository.findAllApprovedAndEnabled()).thenReturn(java.util.List.of());
        when(llmProviderRepository.findByActiveTrue()).thenReturn(java.util.Optional.empty());
        doThrow(new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                "OpenCode sandbox setup failed for agent: connection refused"))
                .when(adkProvider).prepareAgent(any(), any());

        var initializer = new AriaDefaultAgentInitializer(agentRepository, toolDefinitionRepository,
                agentToolRepository, llmProviderRepository, adkProviderRegistry, environment, new AdkSystemProperties());

        assertThatCode(() -> initializer.run(args)).doesNotThrowAnyException();
        verify(adkProvider).prepareAgent(AriaConstants.ARIA_AGENT_ID, ariaAgent);
    }

    @Test
    void survivesPreWarmFailure_whenLangchainProviderThrowsIllegalState() {
        // LangChainAdkProvider.prepareAgent throws IllegalStateException when the Python ADK
        // subprocess never becomes ready — equally transient, must not kill startup.
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});
        when(agentRepository.findById(AriaConstants.ARIA_AGENT_ID)).thenReturn(Optional.of(ariaAgent));
        when(toolDefinitionRepository.findAllApprovedAndEnabled()).thenReturn(java.util.List.of());
        when(llmProviderRepository.findByActiveTrue()).thenReturn(java.util.Optional.empty());
        doThrow(new IllegalStateException("ADK server did not become ready within 60s"))
                .when(adkProvider).prepareAgent(any(), any());

        var initializer = new AriaDefaultAgentInitializer(agentRepository, toolDefinitionRepository,
                agentToolRepository, llmProviderRepository, adkProviderRegistry, environment, new AdkSystemProperties());

        assertThatCode(() -> initializer.run(args)).doesNotThrowAnyException();
        verify(adkProvider).prepareAgent(AriaConstants.ARIA_AGENT_ID, ariaAgent);
    }

    @Test
    void marksAriaDegradedNotHealthy_whenPreWarmFails() {
        // The pre-warm failed, so the agent must not be presented as fully ready:
        // the persisted health stamp after the failure must be DEGRADED, never HEALTHY.
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});
        when(agentRepository.findById(AriaConstants.ARIA_AGENT_ID)).thenReturn(Optional.of(ariaAgent));
        when(toolDefinitionRepository.findAllApprovedAndEnabled()).thenReturn(java.util.List.of());
        when(llmProviderRepository.findByActiveTrue()).thenReturn(java.util.Optional.empty());
        doThrow(new IllegalStateException("ADK down")).when(adkProvider).prepareAgent(any(), any());

        var initializer = new AriaDefaultAgentInitializer(agentRepository, toolDefinitionRepository,
                agentToolRepository, llmProviderRepository, adkProviderRegistry, environment, new AdkSystemProperties());
        initializer.run(args);

        ArgumentCaptor<Agent> captor = ArgumentCaptor.forClass(Agent.class);
        verify(agentRepository, atLeastOnce()).save(captor.capture());
        // last persisted state = post-pre-warm health stamp
        assertThat(captor.getValue().getHealthStatus()).isEqualTo(HealthStatus.DEGRADED);
    }

    @Test
    void createdAriaGetsConfigAndDefaultProvider_evenWhenPreWarmFails() {
        // On CREATE the initializer applies the managed config (taskApprovalRequired=false,
        // round limit, system prompt) and the configured default provider; a pre-warm
        // failure still completes startup and stamps DEGRADED. (Existing Aria records are
        // never rewritten — see the UpsertTest operator-edits-survive coverage.)
        AdkSystemProperties opencodeProps = new AdkSystemProperties();
        opencodeProps.setDefaultProvider("opencode");
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});
        when(agentRepository.findById(AriaConstants.ARIA_AGENT_ID)).thenReturn(Optional.empty());
        when(agentRepository.findAll()).thenReturn(java.util.List.of());
        when(toolDefinitionRepository.findAllApprovedAndEnabled()).thenReturn(java.util.List.of());
        when(llmProviderRepository.findByActiveTrue()).thenReturn(java.util.Optional.empty());
        doThrow(new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                "OpenCode sandbox setup failed"))
                .when(adkProvider).prepareAgent(any(), any());

        var initializer = new AriaDefaultAgentInitializer(agentRepository, toolDefinitionRepository,
                agentToolRepository, llmProviderRepository, adkProviderRegistry, environment, opencodeProps);

        assertThatCode(() -> initializer.run(args)).doesNotThrowAnyException();

        ArgumentCaptor<Agent> captor = ArgumentCaptor.forClass(Agent.class);
        verify(agentRepository, atLeastOnce()).save(captor.capture());
        Agent persisted = captor.getValue();
        assertThat(persisted.getConfig())
                .contains("\"taskApprovalRequired\":false")
                .contains("\"maxToolCallRounds\":15")
                .contains("systemPrompt");
        assertThat(persisted.getAdkProvider()).isEqualTo("opencode");
        assertThat(persisted.getHealthStatus()).isEqualTo(HealthStatus.DEGRADED);
    }

    @Test
    void skipsPreWarm_whenTestProfile() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"test"});
        when(toolDefinitionRepository.findAllApprovedAndEnabled()).thenReturn(java.util.List.of());
        when(llmProviderRepository.findByActiveTrue()).thenReturn(java.util.Optional.empty());
        when(agentRepository.findById(AriaConstants.ARIA_AGENT_ID)).thenReturn(Optional.of(ariaAgent));

        new AriaDefaultAgentInitializer(agentRepository, toolDefinitionRepository,
                agentToolRepository, llmProviderRepository, adkProviderRegistry, environment, new AdkSystemProperties()).run(args);

        verify(adkProvider, never()).prepareAgent(any(), any());
    }

    @Test
    void skipsPreWarm_whenNoopLlmProfile() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"noop-llm"});
        when(toolDefinitionRepository.findAllApprovedAndEnabled()).thenReturn(java.util.List.of());
        when(llmProviderRepository.findByActiveTrue()).thenReturn(java.util.Optional.empty());
        when(agentRepository.findById(AriaConstants.ARIA_AGENT_ID)).thenReturn(Optional.of(ariaAgent));

        new AriaDefaultAgentInitializer(agentRepository, toolDefinitionRepository,
                agentToolRepository, llmProviderRepository, adkProviderRegistry, environment, new AdkSystemProperties()).run(args);

        verify(adkProvider, never()).prepareAgent(any(), any());
    }

    @Test
    void assignsOnlyOrchestrationTools_andPrunesOthers() {
        // #25: Aria must receive only orchestration tools (e.g. run_agent) and any previously-granted
        // non-orchestration tool (e.g. git_push) must be pruned at startup.
        when(environment.getActiveProfiles()).thenReturn(new String[]{"test"});
        ToolDefinition runAgent = ToolDefinition.builder().id("tool-run_agent").name("run_agent").enabled(true).build();
        ToolDefinition gitPush = ToolDefinition.builder().id("tool-git_push").name("git_push").enabled(true).build();
        when(toolDefinitionRepository.findAllApprovedAndEnabled()).thenReturn(java.util.List.of(runAgent, gitPush));
        when(llmProviderRepository.findByActiveTrue()).thenReturn(java.util.Optional.empty());
        when(agentRepository.findById(AriaConstants.ARIA_AGENT_ID)).thenReturn(Optional.of(ariaAgent));
        // Aria currently holds git_push (to be pruned) but not run_agent (to be added).
        when(agentToolRepository.findToolIdsByAgentId(AriaConstants.ARIA_AGENT_ID.toString()))
                .thenReturn(java.util.List.of("tool-git_push"));
        when(agentToolRepository.existsById(new AgentToolId(AriaConstants.ARIA_AGENT_ID.toString(), "tool-run_agent")))
                .thenReturn(false);

        new AriaDefaultAgentInitializer(agentRepository, toolDefinitionRepository,
                agentToolRepository, llmProviderRepository, adkProviderRegistry, environment, new AdkSystemProperties()).run(args);

        verify(agentToolRepository).save(any()); // run_agent assigned
        verify(agentToolRepository).deleteById(new AgentToolId(AriaConstants.ARIA_AGENT_ID.toString(), "tool-git_push"));
    }

    @Test
    void sddPrompt_containsIssueRepoAndFeedbackGuidanceInSavedConfig() {
        // Config (incl. the SDD-aware system prompt) is written on CREATE.
        when(environment.getActiveProfiles()).thenReturn(new String[]{"test"});
        when(toolDefinitionRepository.findAllApprovedAndEnabled()).thenReturn(java.util.List.of());
        when(llmProviderRepository.findByActiveTrue()).thenReturn(java.util.Optional.empty());
        when(agentRepository.findById(AriaConstants.ARIA_AGENT_ID)).thenReturn(Optional.empty());

        new AriaDefaultAgentInitializer(agentRepository, toolDefinitionRepository,
                agentToolRepository, llmProviderRepository, adkProviderRegistry, environment, new AdkSystemProperties()).run(args);

        ArgumentCaptor<Agent> captor = ArgumentCaptor.forClass(Agent.class);
        verify(agentRepository).save(captor.capture());
        String config = captor.getValue().getConfig();
        assertThat(config).contains("pass issueRepo");
        assertThat(config).contains("answer trivial questions");
    }

    // ---- DEGRADED recovery reconciler (called directly — test-friendly) ----

    @Test
    void degradedRecovery_stampsHealthy_whenPreWarmSucceeds() {
        // DEGRADED must not be a terminal state: the reconciler retries the pre-warm
        // and, on success, re-stamps HEALTHY (previously nothing ever reset DEGRADED).
        ariaAgent.setHealthStatus(HealthStatus.DEGRADED);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});
        when(agentRepository.findById(AriaConstants.ARIA_AGENT_ID)).thenReturn(Optional.of(ariaAgent));

        new AriaDefaultAgentInitializer(agentRepository, toolDefinitionRepository,
                agentToolRepository, llmProviderRepository, adkProviderRegistry, environment, new AdkSystemProperties())
                .recoverDegradedAria();

        verify(adkProvider).prepareAgent(AriaConstants.ARIA_AGENT_ID, ariaAgent);
        ArgumentCaptor<Agent> captor = ArgumentCaptor.forClass(Agent.class);
        verify(agentRepository).save(captor.capture());
        assertThat(captor.getValue().getHealthStatus()).isEqualTo(HealthStatus.HEALTHY);
    }

    @Test
    void degradedRecovery_isNoOp_whenAriaHealthy() {
        // HEALTHY agents are not probed and not re-saved by the reconciler.
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});
        when(agentRepository.findById(AriaConstants.ARIA_AGENT_ID)).thenReturn(Optional.of(ariaAgent));

        new AriaDefaultAgentInitializer(agentRepository, toolDefinitionRepository,
                agentToolRepository, llmProviderRepository, adkProviderRegistry, environment, new AdkSystemProperties())
                .recoverDegradedAria();

        verify(adkProvider, never()).prepareAgent(any(), any());
        verify(agentRepository, never()).save(any(Agent.class));
    }

    @Test
    void degradedRecovery_staysDegraded_whenPreWarmStillFails() {
        // A still-failing pre-warm keeps the agent DEGRADED, never throws out of the
        // scheduled method, and does not write a bogus HEALTHY stamp.
        ariaAgent.setHealthStatus(HealthStatus.DEGRADED);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});
        when(agentRepository.findById(AriaConstants.ARIA_AGENT_ID)).thenReturn(Optional.of(ariaAgent));
        doThrow(new IllegalStateException("ADK server still not ready"))
                .when(adkProvider).prepareAgent(any(), any());

        var initializer = new AriaDefaultAgentInitializer(agentRepository, toolDefinitionRepository,
                agentToolRepository, llmProviderRepository, adkProviderRegistry, environment, new AdkSystemProperties());

        assertThatCode(() -> initializer.recoverDegradedAria()).doesNotThrowAnyException();

        assertThat(ariaAgent.getHealthStatus()).isEqualTo(HealthStatus.DEGRADED);
        verify(agentRepository, never()).save(any(Agent.class));
    }
}
