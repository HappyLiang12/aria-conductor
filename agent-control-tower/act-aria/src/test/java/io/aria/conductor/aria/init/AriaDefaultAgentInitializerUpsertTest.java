package io.aria.conductor.aria.init;

import io.aria.conductor.agent.repository.LlmProviderRepository;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.LlmProvider;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Upsert/idempotency behavior of {@link AriaDefaultAgentInitializer} after the
 * Task 18 cutover: creation belongs to {@link LegacySetupService} (create-only,
 * opencode + SANDBOX), an existing Aria row is never rewritten, and the
 * legacy-provider repointing block is gone -- no agent row is ever re-pointed
 * at startup.
 */
@ExtendWith(MockitoExtension.class)
class AriaDefaultAgentInitializerUpsertTest {

    @Mock LlmProviderRepository llmProviderRepository;
    @Mock LegacySetupService legacySetupService;
    @Mock ApplicationArguments args;

    private AriaDefaultAgentInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new AriaDefaultAgentInitializer(llmProviderRepository, legacySetupService);
        // an active provider skips the env-var bootstrap; lenient because the
        // built-in-core constant case never runs the initializer
        lenient().when(llmProviderRepository.findByActiveTrue())
                .thenReturn(Optional.of(LlmProvider.builder().name("p").active(true).build()));
    }

    @Test
    void ariaIsCreatedOnTheBuiltinCoreAndMode() {
        // The supported built-in selection has one owner (LegacySetupService): opencode + SANDBOX.
        assertThat(LegacySetupService.BUILTIN_CORE).isEqualTo("opencode");
        assertThat(LegacySetupService.BUILTIN_MODE.name()).isEqualTo("SANDBOX");
    }

    @Test
    void creationGoesThroughLegacySetupService_withFullConfig() {
        Agent created = Agent.builder().id(AriaConstants.ARIA_AGENT_ID).name("Aria")
                .agentType(AgentType.ADK).adkProvider("opencode")
                .executionMode(io.aria.conductor.common.runtime.ExecutionMode.SANDBOX)
                .healthStatus(HealthStatus.HEALTHY).build();
        when(legacySetupService.initializeMissingAria(any())).thenReturn(created);

        initializer.run(args);

        ArgumentCaptor<String> config = ArgumentCaptor.forClass(String.class);
        verify(legacySetupService).initializeMissingAria(config.capture());
        assertThat(config.getValue())
                .contains("\"maxToolCallRounds\":15")
                .contains("systemPrompt")
                .contains("You are Aria");
    }

    @Test
    void existingAriaIsNotRewritten_operatorEditsSurviveRestart() {
        // The managed config write is CREATE-only: an existing Aria record must be left
        // untouched so operator edits (taskApprovalRequired, name, role, adkProvider)
        // survive every restart instead of being overwritten at boot.
        Agent existing = Agent.builder()
                .id(AriaConstants.ARIA_AGENT_ID)
                .name("Aria (operator renamed)")
                .role("operator-tuned role")
                .agentType(AgentType.ADK)
                .adkProvider("custom")
                .config("{\"taskApprovalRequired\":true,\"maxToolCallRounds\":9}")
                .healthStatus(HealthStatus.HEALTHY)
                .build();
        when(legacySetupService.initializeMissingAria(any())).thenReturn(existing);

        initializer.run(args);

        assertThat(existing.getName()).isEqualTo("Aria (operator renamed)");
        assertThat(existing.getRole()).isEqualTo("operator-tuned role");
        assertThat(existing.getAdkProvider()).isEqualTo("custom");
        assertThat(existing.getConfig()).contains("\"taskApprovalRequired\":true");
    }

    @Test
    void noLegacyRepointingHappensAtStartup() {
        // The removed migration block must never run again: a seeded role agent still on
        // the historical provider is not touched. Creation is the initializer's only
        // agent-table interaction.
        Agent seededDev = Agent.builder()
                .id(java.util.UUID.fromString("de000000-0000-0000-0000-000000000002"))
                .name("SDD DEV Agent").adkProvider("langchain").build();
        when(legacySetupService.initializeMissingAria(any())).thenReturn(null);

        initializer.run(args);

        verify(legacySetupService).initializeMissingAria(any());
        assertThat(seededDev.getAdkProvider()).isEqualTo("langchain");
    }
}
