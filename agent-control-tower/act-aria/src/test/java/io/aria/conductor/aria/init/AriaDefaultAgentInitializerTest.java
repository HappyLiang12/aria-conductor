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
import io.aria.conductor.execution.adk.LangChainAdkProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.env.Environment;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AriaDefaultAgentInitializerTest {

    @Mock AgentRepository agentRepository;
    @Mock ToolDefinitionRepository toolDefinitionRepository;
    @Mock AgentToolRepository agentToolRepository;
    @Mock LlmProviderRepository llmProviderRepository;
    @Mock LangChainAdkProvider adkProvider;
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
    }

    @Test
    void preWarmsAdk_whenNotTestProfile() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});
        when(agentRepository.findById(AriaConstants.ARIA_AGENT_ID)).thenReturn(Optional.of(ariaAgent));
        when(toolDefinitionRepository.findAllApprovedAndEnabled()).thenReturn(java.util.List.of());
        when(llmProviderRepository.findByActiveTrue()).thenReturn(java.util.Optional.empty());

        new AriaDefaultAgentInitializer(agentRepository, toolDefinitionRepository,
                agentToolRepository, llmProviderRepository, adkProvider, environment).run(args);

        verify(adkProvider).prepareAgent(AriaConstants.ARIA_AGENT_ID, ariaAgent);
    }

    @Test
    void failsToStart_whenAdkPreWarmFails() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});
        when(agentRepository.findById(AriaConstants.ARIA_AGENT_ID)).thenReturn(Optional.of(ariaAgent));
        when(toolDefinitionRepository.findAllApprovedAndEnabled()).thenReturn(java.util.List.of());
        when(llmProviderRepository.findByActiveTrue()).thenReturn(java.util.Optional.empty());
        doThrow(new RuntimeException("ADK down")).when(adkProvider).prepareAgent(any(), any());

        var initializer = new AriaDefaultAgentInitializer(agentRepository, toolDefinitionRepository,
                agentToolRepository, llmProviderRepository, adkProvider, environment);

        assertThatThrownBy(() -> initializer.run(args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADK pre-warm failed for Aria");
    }

    @Test
    void skipsPreWarm_whenTestProfile() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"test"});
        when(toolDefinitionRepository.findAllApprovedAndEnabled()).thenReturn(java.util.List.of());
        when(llmProviderRepository.findByActiveTrue()).thenReturn(java.util.Optional.empty());
        when(agentRepository.findById(AriaConstants.ARIA_AGENT_ID)).thenReturn(Optional.of(ariaAgent));

        new AriaDefaultAgentInitializer(agentRepository, toolDefinitionRepository,
                agentToolRepository, llmProviderRepository, adkProvider, environment).run(args);

        verify(adkProvider, never()).prepareAgent(any(), any());
    }

    @Test
    void skipsPreWarm_whenNoopLlmProfile() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"noop-llm"});
        when(toolDefinitionRepository.findAllApprovedAndEnabled()).thenReturn(java.util.List.of());
        when(llmProviderRepository.findByActiveTrue()).thenReturn(java.util.Optional.empty());
        when(agentRepository.findById(AriaConstants.ARIA_AGENT_ID)).thenReturn(Optional.of(ariaAgent));

        new AriaDefaultAgentInitializer(agentRepository, toolDefinitionRepository,
                agentToolRepository, llmProviderRepository, adkProvider, environment).run(args);

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
                agentToolRepository, llmProviderRepository, adkProvider, environment).run(args);

        verify(agentToolRepository).save(any()); // run_agent assigned
        verify(agentToolRepository).deleteById(new AgentToolId(AriaConstants.ARIA_AGENT_ID.toString(), "tool-git_push"));
    }
}
