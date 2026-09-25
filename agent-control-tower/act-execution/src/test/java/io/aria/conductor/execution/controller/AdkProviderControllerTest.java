package io.aria.conductor.execution.controller;

import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.execution.adk.AdkProvider;
import io.aria.conductor.execution.adk.AdkProviderRegistry;
import io.aria.conductor.execution.adk.AdkSystemProperties;
import io.aria.conductor.execution.adk.TaskExecutionException;
import io.aria.conductor.execution.adk.opencode.OpenCodeAdkProvider;
import io.aria.conductor.execution.runtime.CoreAdapter;
import io.aria.conductor.execution.runtime.CoreAdapters;
import io.aria.conductor.execution.runtime.CoreCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@code GET /api/v1/adk/providers} and {@code GET /api/v1/adk/providers/{id}/health}.
 *
 * <p>Standalone unit test (no Spring context): the integration suite replaces
 * {@link AdkProviderRegistry} with a {@code @MockBean}, so the real registry's
 * provider listing is exercised here instead.
 *
 * <p>Task 18 cutover: the provider inventory is the production core catalog
 * ({@code opencode} + {@code qoder}) and the adapter registry that admission
 * accepts -- never a provider bean list. The unit test therefore drives the
 * real {@link CoreCatalog}/{@link CoreAdapters} value types.
 */
@ExtendWith(MockitoExtension.class)
class AdkProviderControllerTest {

    @Mock AdkProviderRegistry registry;
    @Mock AdkSystemProperties systemProperties;
    @Mock AdkProvider openCode;
    @Mock OpenCodeAdkProvider openCodeProvider;

    private static CoreCatalog catalogOf(String... coreIds) {
        Map<String, java.util.Set<ExecutionMode>> modes = new java.util.LinkedHashMap<>();
        for (String coreId : coreIds) {
            modes.put(coreId, EnumSet.of(ExecutionMode.HOST, ExecutionMode.SANDBOX));
        }
        return new CoreCatalog(modes);
    }

    private static CoreAdapters adaptersOf(String... coreIds) {
        List<CoreAdapter> adapters = java.util.Arrays.stream(coreIds)
                .map(AdkProviderControllerTest::anonymousAdapter)
                .collect(Collectors.toList());
        return new CoreAdapters(adapters);
    }

    private static CoreAdapter anonymousAdapter(String coreId) {
        return new CoreAdapter() {
            @Override
            public String coreId() {
                return coreId;
            }

            @Override
            public io.aria.conductor.execution.runtime.CoreCapabilities capabilities(
                    ExecutionMode mode) {
                throw new UnsupportedOperationException();
            }

            @Override
            public io.aria.conductor.execution.runtime.LaunchProfile launchProfile(
                    io.aria.conductor.execution.runtime.ExecutionSpec spec,
                    io.aria.conductor.execution.runtime.PreparedEnvironment environment,
                    io.aria.conductor.execution.runtime.SecretBundle credentials) {
                throw new UnsupportedOperationException();
            }

            @Override
            public io.aria.conductor.execution.runtime.CoreSession open(
                    io.aria.conductor.execution.runtime.RuntimeHandle handle,
                    io.aria.conductor.execution.runtime.ExecutionSpec spec,
                    io.aria.conductor.execution.runtime.SecretBundle credentials) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    void listProviders_servesExactlyTheProductionCoreCatalog_withOpencodeAsDefault() {
        // The production default is part of the cutover contract: opencode, and it
        // must be the one (and only) row the inventory marks as default.
        AdkSystemProperties realProperties = new AdkSystemProperties();
        assertThat(realProperties.getDefaultProvider()).isEqualTo("opencode");
        AdkProviderController controller = new AdkProviderController(registry, realProperties,
                catalogOf("qoder", "opencode"), adaptersOf("opencode", "qoder"));

        ResponseEntity<List<Map<String, Object>>> response = controller.listProviders();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        List<Map<String, Object>> body = response.getBody();
        assertThat(body).hasSize(2);
        Map<String, Map<String, Object>> byId = body.stream()
                .collect(Collectors.toMap(e -> (String) e.get("id"), e -> e));
        assertThat(byId.keySet()).containsExactlyInAnyOrder("opencode", "qoder");

        assertThat(byId.get("opencode").get("displayName")).isEqualTo("OpenCode");
        assertThat(byId.get("opencode").get("supportsTaskExecution")).isEqualTo(true);
        assertThat(byId.get("opencode").get("isDefault")).isEqualTo(true);

        assertThat(byId.get("qoder").get("displayName")).isEqualTo("Qoder");
        assertThat(byId.get("qoder").get("supportsTaskExecution")).isEqualTo(true);
        assertThat(byId.get("qoder").get("isDefault")).isEqualTo(false);

        // exactly one default row, and it is opencode
        assertThat(body.stream().filter(e -> Boolean.TRUE.equals(e.get("isDefault")))
                .map(e -> e.get("id"))).containsExactly("opencode");
    }

    @Test
    void listProviders_reportsCatalogEntriesWithoutAnAdapterAsNonExecutable() {
        // An id the catalog knows but no adapter serves is refused by admission and
        // must not be advertised as task-executing.
        AdkProviderController controller = new AdkProviderController(registry, systemProperties,
                catalogOf("opencode", "legacy-core"), adaptersOf("opencode"));
        when(systemProperties.getDefaultProvider()).thenReturn("opencode");

        ResponseEntity<List<Map<String, Object>>> response = controller.listProviders();

        Map<String, Map<String, Object>> byId = response.getBody().stream()
                .collect(Collectors.toMap(e -> (String) e.get("id"), e -> e));
        assertThat(byId.get("opencode").get("supportsTaskExecution")).isEqualTo(true);
        assertThat(byId.get("legacy-core").get("supportsTaskExecution")).isEqualTo(false);
        assertThat(byId.get("legacy-core").get("isDefault")).isEqualTo(false);
    }

    @Test
    void listProviders_fallsBackToCapitalizedIdForUnknownDisplayName() {
        AdkProviderController controller = new AdkProviderController(registry, systemProperties,
                catalogOf("future-core"), adaptersOf("future-core"));
        when(systemProperties.getDefaultProvider()).thenReturn("opencode");

        ResponseEntity<List<Map<String, Object>>> response = controller.listProviders();

        Map<String, Object> entry = response.getBody().get(0);
        assertThat(entry.get("displayName")).isEqualTo("Future-core");
    }

    @Test
    void health_returnsHealthyForRegisteredProvider() {
        AdkProviderController controller = new AdkProviderController(registry, systemProperties,
                catalogOf("opencode"), adaptersOf("opencode"));
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
        AdkProviderController controller = new AdkProviderController(registry, systemProperties,
                catalogOf("opencode"), adaptersOf("opencode"));
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
        AdkProviderController controller = new AdkProviderController(registry, systemProperties,
                catalogOf("opencode"), adaptersOf("opencode"));
        when(registry.getProvider("opencode")).thenReturn(openCode);
        when(openCode.isServiceHealthy()).thenReturn(true);

        controller.getProviderHealth("opencode");

        verify(openCode).isServiceHealthy();
    }

    @Test
    void health_returnsNotFoundForUnknownProvider() {
        AdkProviderController controller = new AdkProviderController(registry, systemProperties,
                catalogOf("opencode"), adaptersOf("opencode"));
        when(registry.getProvider("nope")).thenReturn(null);

        ResponseEntity<Map<String, Object>> response = controller.getProviderHealth("nope");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void health_servesTheCoreCatalogDescriptorForACoreWithoutAProviderBean() {
        // qoder is a registered production core but has no AdkProvider bean: the health
        // route must answer from the per-core descriptor instead of 404ing (which the UI
        // renders as a permanently unhealthy core).
        CoreAdapter qoder = org.mockito.Mockito.mock(CoreAdapter.class);
        when(qoder.coreId()).thenReturn("qoder");
        when(qoder.serviceHealthy()).thenReturn(true);
        AdkProviderController controller = new AdkProviderController(registry, systemProperties,
                catalogOf("opencode", "qoder"), new CoreAdapters(List.of(anonymousAdapter("opencode"), qoder)));

        ResponseEntity<Map<String, Object>> response = controller.getProviderHealth("qoder");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody())
                .containsEntry("providerId", "qoder")
                .containsEntry("healthy", true);
        verify(qoder).serviceHealthy();
    }

    @Test
    void health_servesTheCoreCatalogDescriptorVerbatim() {
        // The descriptor's answer is the response -- never a hard-coded healthy=true.
        CoreAdapter qoder = org.mockito.Mockito.mock(CoreAdapter.class);
        when(qoder.coreId()).thenReturn("qoder");
        when(qoder.serviceHealthy()).thenReturn(false);
        AdkProviderController controller = new AdkProviderController(registry, systemProperties,
                catalogOf("qoder"), new CoreAdapters(List.of(qoder)));

        ResponseEntity<Map<String, Object>> response = controller.getProviderHealth("qoder");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).containsEntry("healthy", false);
    }

    @Test
    void health_prefersTheProviderProbeOverTheAdapterDescriptor() {
        // A core served by a provider bean keeps its service probe (the OpenSandbox
        // reachability answer for opencode); the adapter descriptor must not shadow it.
        CoreAdapter opencodeAdapter = org.mockito.Mockito.mock(CoreAdapter.class);
        when(opencodeAdapter.coreId()).thenReturn("opencode");
        when(registry.getProvider("opencode")).thenReturn(openCode);
        when(openCode.isServiceHealthy()).thenReturn(false);
        AdkProviderController controller = new AdkProviderController(registry, systemProperties,
                catalogOf("opencode"), new CoreAdapters(List.of(opencodeAdapter)));

        ResponseEntity<Map<String, Object>> response = controller.getProviderHealth("opencode");

        assertThat(response.getBody()).containsEntry("healthy", false);
        verify(openCode).isServiceHealthy();
        verify(opencodeAdapter, org.mockito.Mockito.never()).serviceHealthy();
    }

    @Test
    void health_returnsNotFoundWhenNeitherProviderNorAdapterDescribesTheCore() {
        AdkProviderController controller = new AdkProviderController(registry, systemProperties,
                catalogOf("opencode", "ghost-core"), adaptersOf("opencode"));
        when(registry.getProvider("ghost-core")).thenReturn(null);

        ResponseEntity<Map<String, Object>> response = controller.getProviderHealth("ghost-core");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    // ---- R3-F2 sandbox diagnosis endpoint ----

    @Test
    void diagnosis_returnsTextPlainDiagnosis() {
        AdkProviderController controller = new AdkProviderController(registry, systemProperties,
                catalogOf("opencode"), adaptersOf("opencode"));
        UUID agentId = UUID.randomUUID();
        when(registry.getProvider("opencode")).thenReturn(openCodeProvider);
        when(openCodeProvider.diagnoseSandbox(agentId)).thenReturn("== metrics ==\nok");

        ResponseEntity<Object> response = controller.diagnoseOpenCodeSandbox(agentId);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);
        assertThat(response.getBody()).isEqualTo("== metrics ==\nok");
    }

    @Test
    void diagnosis_returnsNotFoundForUnknownProvider() {
        AdkProviderController controller = new AdkProviderController(registry, systemProperties,
                catalogOf("opencode"), adaptersOf("opencode"));
        when(registry.getProvider("opencode")).thenReturn(null);

        ResponseEntity<Object> response = controller.diagnoseOpenCodeSandbox(UUID.randomUUID());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void diagnosis_returnsNotFoundWhenNoSandboxForAgent() {
        AdkProviderController controller = new AdkProviderController(registry, systemProperties,
                catalogOf("opencode"), adaptersOf("opencode"));
        UUID agentId = UUID.randomUUID();
        when(registry.getProvider("opencode")).thenReturn(openCodeProvider);
        when(openCodeProvider.diagnoseSandbox(agentId))
                .thenThrow(new TaskExecutionException(
                        TaskExecutionException.Cause.SANDBOX_UNAVAILABLE, "no sandbox"));

        ResponseEntity<Object> response = controller.diagnoseOpenCodeSandbox(agentId);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
