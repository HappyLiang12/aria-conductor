package io.aria.conductor.aria.init;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.LlmProviderRepository;
import io.aria.conductor.aria.AriaConstants;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.LlmProvider;
import io.aria.conductor.common.model.ToolDefinition;
import io.aria.conductor.common.repository.AgentToolRepository;
import io.aria.conductor.common.repository.ToolDefinitionRepository;
import io.aria.conductor.execution.adk.LangChainAdkProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Upsert/idempotency behavior of {@link AriaDefaultAgentInitializer}:
 * creation vs in-place update of the Aria agent, no duplicate tool
 * assignments, and the legacy-provider migration filter.
 * AriaDefaultAgentInitializerTest covers pre-warm and tool pruning.
 */
@ExtendWith(MockitoExtension.class)
class AriaDefaultAgentInitializerUpsertTest {

    @Mock AgentRepository agentRepository;
    @Mock ToolDefinitionRepository toolDefinitionRepository;
    @Mock AgentToolRepository agentToolRepository;
    @Mock LlmProviderRepository llmProviderRepository;
    @Mock LangChainAdkProvider adkProvider;
    @Mock Environment environment;
    @Mock ApplicationArguments args;

    private AriaDefaultAgentInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new AriaDefaultAgentInitializer(agentRepository, toolDefinitionRepository,
                agentToolRepository, llmProviderRepository, adkProvider, environment);
        // "test" profile skips ADK pre-warm; active provider skips the env-var bootstrap
        when(environment.getActiveProfiles()).thenReturn(new String[]{"test"});
        when(llmProviderRepository.findByActiveTrue())
                .thenReturn(Optional.of(LlmProvider.builder().name("p").active(true).build()));
        lenient().when(toolDefinitionRepository.findAllApprovedAndEnabled()).thenReturn(List.of());
    }

    @Test
    void createsAriaWithFullConfigWhenMissing() {
        when(agentRepository.findById(AriaConstants.ARIA_AGENT_ID)).thenReturn(Optional.empty());

        initializer.run(args);

        ArgumentCaptor<Agent> captor = ArgumentCaptor.forClass(Agent.class);
        verify(agentRepository).save(captor.capture());
        Agent saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(AriaConstants.ARIA_AGENT_ID);
        assertThat(saved.getName()).isEqualTo("Aria");
        assertThat(saved.getAgentType()).isEqualTo(AgentType.NATIVE);
        assertThat(saved.getAdkProvider()).isEqualTo("langchain");
        assertThat(saved.getHealthStatus()).isEqualTo(HealthStatus.HEALTHY);
        assertThat(saved.getUpdatedAt()).isNotNull();
        // config must embed both the round limit and the full system prompt
        assertThat(saved.getConfig())
                .contains("\"maxToolCallRounds\":15")
                .contains("systemPrompt")
                .contains("You are Aria");
    }

    @Test
    void updatesExistingAriaInPlaceInsteadOfCreatingDuplicate() {
        Agent existing = Agent.builder()
                .id(AriaConstants.ARIA_AGENT_ID)
                .name("Old Aria")
                .role("outdated role")
                .agentType(AgentType.NATIVE)
                .adkProvider("adk-py")
                .config("{}")
                .healthStatus(HealthStatus.HEALTHY)
                .build();
        when(agentRepository.findById(AriaConstants.ARIA_AGENT_ID))
                .thenReturn(Optional.of(existing));

        initializer.run(args);

        ArgumentCaptor<Agent> captor = ArgumentCaptor.forClass(Agent.class);
        verify(agentRepository, times(1)).save(captor.capture());
        // same instance is refreshed — no second Aria row is created
        assertThat(captor.getValue()).isSameAs(existing);
        assertThat(existing.getName()).isEqualTo("Aria");
        assertThat(existing.getAdkProvider()).isEqualTo("langchain");
        assertThat(existing.getConfig()).contains("maxToolCallRounds");
        assertThat(existing.getRole()).isNotEqualTo("outdated role");
    }

    @Test
    void alreadyAssignedToolIsNotAssignedAgain() {
        when(agentRepository.findById(AriaConstants.ARIA_AGENT_ID)).thenReturn(Optional.empty());
        ToolDefinition runAgent = ToolDefinition.builder()
                .id("tool-run_agent").name("run_agent").enabled(true).build();
        when(toolDefinitionRepository.findAllApprovedAndEnabled()).thenReturn(List.of(runAgent));
        when(agentToolRepository.existsById(any())).thenReturn(true);
        when(agentToolRepository.findToolIdsByAgentId(AriaConstants.ARIA_AGENT_ID.toString()))
                .thenReturn(List.of("tool-run_agent"));

        initializer.run(args);

        // second startup must neither re-assign nor prune the already-correct grant
        verify(agentToolRepository, never()).save(any());
        verify(agentToolRepository, never()).deleteById(any());
    }

    @Test
    void nonLegacyProviderAgentsAreNotTouchedByMigration() {
        when(agentRepository.findById(AriaConstants.ARIA_AGENT_ID)).thenReturn(Optional.empty());
        Agent worker = Agent.builder()
                .id(UUID.randomUUID()).name("Worker").adkProvider("custom").build();
        when(agentRepository.findAll()).thenReturn(List.of(worker));

        initializer.run(args);

        // only the Aria upsert itself is persisted; the custom-provider worker is untouched
        verify(agentRepository, times(1)).save(any(Agent.class));
        assertThat(worker.getAdkProvider()).isEqualTo("custom");
    }

    @Test
    void langchainProviderAgentsAreResavedByMigration() {
        // Pins the current (odd) behavior: the "legacy" filter matches "langchain"
        // itself, so langchain agents are rewritten langchain -> langchain each boot.
        when(agentRepository.findById(AriaConstants.ARIA_AGENT_ID)).thenReturn(Optional.empty());
        Agent worker = Agent.builder()
                .id(UUID.randomUUID()).name("Worker").adkProvider("LangChain").build();
        when(agentRepository.findAll()).thenReturn(List.of(worker));

        initializer.run(args);

        // one save for Aria + one for the case-insensitively matched worker
        verify(agentRepository, times(2)).save(any(Agent.class));
        assertThat(worker.getAdkProvider()).isEqualTo("langchain");
    }
}
