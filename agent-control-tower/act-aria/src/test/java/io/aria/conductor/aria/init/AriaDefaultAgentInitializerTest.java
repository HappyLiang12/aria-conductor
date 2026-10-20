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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Startup behavior of {@link AriaDefaultAgentInitializer} after the Task 18
 * cutover: the Aria row is created through {@link LegacySetupService} on the
 * supported {@code opencode}+{@code SANDBOX} built-in, an existing row is never
 * rewritten, a step failure degrades instead of killing the boot, and no
 * provider is ever pre-warmed (the permanent ADK pre-warm and its DEGRADED
 * reconciler are gone with the LangChain runtime).
 */
@ExtendWith(MockitoExtension.class)
class AriaDefaultAgentInitializerTest {

    @Mock LlmProviderRepository llmProviderRepository;
    @Mock LegacySetupService legacySetupService;
    @Mock ApplicationArguments args;

    private Agent ariaAgent;

    @BeforeEach
    void setUp() {
        ariaAgent = Agent.builder()
                .id(AriaConstants.ARIA_AGENT_ID)
                .name("Aria")
                .agentType(AgentType.ADK)
                .adkProvider(LegacySetupService.BUILTIN_CORE)
                .executionMode(LegacySetupService.BUILTIN_MODE)
                .config("{\"maxToolCallRounds\":15}")
                .healthStatus(HealthStatus.HEALTHY)
                .build();
    }

    @Test
    void createsAriaThroughLegacySetupService_whenMissing() {
        when(legacySetupService.initializeMissingAria(any())).thenReturn(ariaAgent);
        when(llmProviderRepository.findByActiveTrue()).thenReturn(Optional.of(
                LlmProvider.builder().name("p").active(true).build()));

        new AriaDefaultAgentInitializer(llmProviderRepository, legacySetupService).run(args);

        ArgumentCaptor<String> config = ArgumentCaptor.forClass(String.class);
        verify(legacySetupService).initializeMissingAria(config.capture());
        assertThat(config.getValue())
                .contains("\"taskApprovalRequired\":false")
                .contains("\"maxToolCallRounds\":15")
                .contains("You are Aria");
    }

    @Test
    void permanentPreWarmAndItsReconcilerAreGone() {
        // The Task 18 removal assertion: no permanent pre-warm seam and no DEGRADED
        // recovery reconciler survives from the LangChain era.
        assertThatThrownBy(() -> AriaDefaultAgentInitializer.class.getMethod("recoverDegradedAria"))
                .isInstanceOf(NoSuchMethodException.class);
    }

    @Test
    void stepFailureDuringInitialization_degradesAndBootsInsteadOfAborting() {
        // Boot hardening: the initializer runs in the HIGHEST_PRECEDENCE ApplicationRunner —
        // an exception there must be caught (ERROR logged) and startup must continue;
        // every step is idempotent and retried on the next boot.
        when(legacySetupService.initializeMissingAria(any()))
                .thenThrow(new RuntimeException("db hiccup"));

        AriaDefaultAgentInitializer initializer =
                new AriaDefaultAgentInitializer(llmProviderRepository, legacySetupService);

        // no exception escapes
        assertThatCode(() -> initializer.run(args)).doesNotThrowAnyException();
    }

    @Test
    void providerBootstrapFailureIsCaught() {
        when(legacySetupService.initializeMissingAria(any())).thenReturn(ariaAgent);
        when(llmProviderRepository.findByActiveTrue()).thenThrow(new RuntimeException("db hiccup"));

        AriaDefaultAgentInitializer initializer =
                new AriaDefaultAgentInitializer(llmProviderRepository, legacySetupService);

        assertThatCode(() -> initializer.run(args)).doesNotThrowAnyException();
    }
}
