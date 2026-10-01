package io.aria.conductor.execution.runtime;

import io.aria.conductor.agent.repository.LlmProviderRepository;
import io.aria.conductor.common.model.LlmProvider;
import io.aria.conductor.execution.runtime.core.OpenCodeCoreAdapter;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The production wiring of a governed opencode run: which model-provider
 * environment reaches the launch, and which provider the governed configuration
 * serves with.
 */
class CoreRuntimeConfigurationTest {

    @Test
    void openCodeModelProviderEnvironmentKeepsOnlySetValues() {
        Map<String, String> declared = new LinkedHashMap<>();
        declared.put("DEEPSEEK_API_KEY", "");
        declared.put("LLM_API_KEY", "sk-live-value");
        declared.put("GH_TOKEN", "  ");

        assertThat(CoreRuntimeConfiguration.openCodeModelProviderEnvironment(declared))
                .containsExactly(Map.entry("LLM_API_KEY", "sk-live-value"));
    }

    @Test
    void openCodeModelProviderReadsTheActiveRowAndSanitizesItsName() {
        LlmProvider active = LlmProvider.builder()
                .name("My Gateway!").baseUrl("https://gw.example.com/v1")
                .defaultModel("acme-flash").active(true)
                .build();

        assertThat(CoreRuntimeConfiguration.openCodeModelProvider(repositoryWith(active)))
                .isEqualTo(new OpenCodeCoreAdapter.ModelProvider(
                        "my-gateway", "acme-flash", "https://gw.example.com/v1"));
    }

    @Test
    void openCodeModelProviderFallsBackPerMemberAndWithoutARow() {
        assertThat(CoreRuntimeConfiguration.openCodeModelProvider(repositoryWith(null)))
                .isEqualTo(OpenCodeCoreAdapter.ModelProvider.deepseekFallback());

        LlmProvider blank = LlmProvider.builder()
                .name("  ").baseUrl(" ").defaultModel(null).active(true)
                .build();
        assertThat(CoreRuntimeConfiguration.openCodeModelProvider(repositoryWith(blank)))
                .isEqualTo(OpenCodeCoreAdapter.ModelProvider.deepseekFallback());
    }

    private static LlmProviderRepository repositoryWith(LlmProvider provider) {
        LlmProviderRepository repository = mock(LlmProviderRepository.class);
        when(repository.findByActiveTrue()).thenReturn(Optional.ofNullable(provider));
        return repository;
    }
}
