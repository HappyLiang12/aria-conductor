package io.aria.conductor.execution.controller;

import io.aria.conductor.execution.adk.AdkProvider;
import io.aria.conductor.execution.adk.AdkProviderRegistry;
import io.aria.conductor.execution.adk.AdkSystemProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@code GET /api/v1/adk/providers} and {@code GET /api/v1/adk/providers/{id}/health}.
 *
 * <p>Standalone unit test (no Spring context): the integration suite replaces
 * {@link AdkProviderRegistry} with a {@code @MockBean}, so the real registry's
 * provider listing is exercised here instead.
 */
@ExtendWith(MockitoExtension.class)
class AdkProviderControllerTest {

    @Mock AdkProviderRegistry registry;
    @Mock AdkSystemProperties systemProperties;
    @Mock AdkProvider openCode;
    @Mock AdkProvider langChain;

    @InjectMocks
    AdkProviderController controller;

    @Test
    void listProviders_includesOpenCodeAndLangChainWithDisplayNames() {
        when(systemProperties.getDefaultProvider()).thenReturn("langchain");
        when(registry.getProviderIds()).thenReturn(List.of("opencode", "langchain"));
        when(registry.getProvider("opencode")).thenReturn(openCode);
        when(registry.getProvider("langchain")).thenReturn(langChain);
        when(openCode.supportsTaskExecution()).thenReturn(true);
        when(langChain.supportsTaskExecution()).thenReturn(false);

        ResponseEntity<List<Map<String, Object>>> response = controller.listProviders();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        List<Map<String, Object>> body = response.getBody();
        assertThat(body).hasSize(2);

        Map<String, Object> oc = body.get(0);
        assertThat(oc.get("id")).isEqualTo("opencode");
        assertThat(oc.get("displayName")).isEqualTo("OpenCode");
        assertThat(oc.get("supportsTaskExecution")).isEqualTo(true);
        assertThat(oc.get("isDefault")).isEqualTo(false);

        Map<String, Object> lc = body.get(1);
        assertThat(lc.get("id")).isEqualTo("langchain");
        assertThat(lc.get("displayName")).isEqualTo("LangChain ADK");
        assertThat(lc.get("supportsTaskExecution")).isEqualTo(false);
        assertThat(lc.get("isDefault")).isEqualTo(true);
    }

    @Test
    void listProviders_fallsBackToCapitalizedIdForUnknownDisplayName() {
        when(systemProperties.getDefaultProvider()).thenReturn("langchain");
        when(registry.getProviderIds()).thenReturn(List.of("future-provider"));
        when(registry.getProvider("future-provider")).thenReturn(openCode);
        when(openCode.supportsTaskExecution()).thenReturn(false);

        ResponseEntity<List<Map<String, Object>>> response = controller.listProviders();

        Map<String, Object> entry = response.getBody().get(0);
        assertThat(entry.get("displayName")).isEqualTo("Future-provider");
    }

    @Test
    void health_returnsHealthyForRegisteredProvider() {
        when(registry.getProvider("opencode")).thenReturn(openCode);
        when(openCode.isServiceHealthy()).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.getProviderHealth("opencode");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody())
                .containsEntry("providerId", "opencode")
                .containsEntry("healthy", true);
    }

    @Test
    void health_returnsUnhealthy_whenServiceProbeFails() {
        // Simulates e.g. the OpenSandbox server being down: the service-level
        // probe reports false and the endpoint must NOT hard-code healthy=true.
        when(registry.getProvider("opencode")).thenReturn(openCode);
        when(openCode.isServiceHealthy()).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = controller.getProviderHealth("opencode");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody())
                .containsEntry("providerId", "opencode")
                .containsEntry("healthy", false);
    }

    @Test
    void health_delegatesToServiceLevelProbe() {
        when(registry.getProvider("langchain")).thenReturn(langChain);
        when(langChain.isServiceHealthy()).thenReturn(true);

        controller.getProviderHealth("langchain");

        verify(langChain).isServiceHealthy();
    }

    @Test
    void health_returnsNotFoundForUnknownProvider() {
        when(registry.getProvider("nope")).thenReturn(null);

        ResponseEntity<Map<String, Object>> response = controller.getProviderHealth("nope");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
