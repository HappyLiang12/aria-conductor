package io.aria.conductor.execution.controller;

import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.execution.adk.AdkSystemProperties;
import io.aria.conductor.execution.runtime.CoreAdapter;
import io.aria.conductor.execution.runtime.CoreAdapters;
import io.aria.conductor.execution.runtime.CoreCapabilities;
import io.aria.conductor.execution.runtime.CoreCatalog;
import io.aria.conductor.execution.runtime.CoreSession;
import io.aria.conductor.execution.runtime.ExecutionSpec;
import io.aria.conductor.execution.runtime.LaunchProfile;
import io.aria.conductor.execution.runtime.PreparedEnvironment;
import io.aria.conductor.execution.runtime.RuntimeHandle;
import io.aria.conductor.execution.runtime.SecretBundle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@code GET /api/v1/adk/providers} and {@code GET /api/v1/adk/providers/{id}/health}.
 *
 * <p>Standalone unit test (no Spring context): the inventory is the production
 * core catalog ({@code opencode} + {@code qoder}) and the adapter registry that
 * admission accepts — never a provider bean list. The unit test therefore drives
 * the real {@link CoreCatalog}/{@link CoreAdapters} value types.
 */
@ExtendWith(MockitoExtension.class)
class AdkProviderControllerTest {

    @Mock CoreAdapter opencodeAdapter;

    private static CoreCatalog catalogOf(String... coreIds) {
        Map<String, Set<ExecutionMode>> modes = new LinkedHashMap<>();
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
            public CoreCapabilities capabilities(ExecutionMode mode) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LaunchProfile launchProfile(ExecutionSpec spec, PreparedEnvironment environment,
                    SecretBundle credentials) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CoreSession open(RuntimeHandle handle, ExecutionSpec spec, SecretBundle credentials) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static AdkProviderController controller(CoreCatalog catalog, CoreAdapters adapters) {
        return new AdkProviderController(new AdkSystemProperties(), catalog, adapters);
    }

    @Test
    void listProviders_servesExactlyTheProductionCoreCatalog_withOpencodeAsDefault() {
        // The production default is part of the cutover contract: opencode, and it
        // must be the one (and only) row the inventory marks as default.
        AdkProviderController controller = controller(
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

        assertThat(body.stream().filter(e -> Boolean.TRUE.equals(e.get("isDefault")))
                .map(e -> e.get("id"))).containsExactly("opencode");
    }

    @Test
    void listProviders_reportsCatalogEntriesWithoutAnAdapterAsNonExecutable() {
        // An id the catalog knows but no adapter serves is refused by admission and
        // must not be advertised as task-executing.
        AdkProviderController controller = controller(
                catalogOf("opencode", "legacy-core"), adaptersOf("opencode"));

        ResponseEntity<List<Map<String, Object>>> response = controller.listProviders();

        Map<String, Map<String, Object>> byId = response.getBody().stream()
                .collect(Collectors.toMap(e -> (String) e.get("id"), e -> e));
        assertThat(byId.get("opencode").get("supportsTaskExecution")).isEqualTo(true);
        assertThat(byId.get("legacy-core").get("supportsTaskExecution")).isEqualTo(false);
        assertThat(byId.get("legacy-core").get("isDefault")).isEqualTo(false);
    }

    @Test
    void listProviders_fallsBackToCapitalizedIdForUnknownDisplayName() {
        AdkProviderController controller = controller(
                catalogOf("future-core"), adaptersOf("future-core"));

        ResponseEntity<List<Map<String, Object>>> response = controller.listProviders();

        Map<String, Object> entry = response.getBody().get(0);
        assertThat(entry.get("displayName")).isEqualTo("Future-core");
    }

    @Test
    void health_returnsTheAdapterServiceDescriptor() {
        when(opencodeAdapter.coreId()).thenReturn("opencode");
        when(opencodeAdapter.serviceHealthy()).thenReturn(true);
        AdkProviderController controller = controller(
                catalogOf("opencode"), new CoreAdapters(List.of(opencodeAdapter)));

        ResponseEntity<Map<String, Object>> response = controller.getProviderHealth("opencode");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody())
                .containsEntry("providerId", "opencode")
                .containsEntry("healthy", true);
        verify(opencodeAdapter).serviceHealthy();
    }

    @Test
    void health_returnsUnhealthy_whenServiceProbeFails() {
        // Simulates e.g. the OpenSandbox server being down: the descriptor reports false
        // and the endpoint must NOT hard-code healthy=true.
        when(opencodeAdapter.coreId()).thenReturn("opencode");
        when(opencodeAdapter.serviceHealthy()).thenReturn(false);
        AdkProviderController controller = controller(
                catalogOf("opencode"), new CoreAdapters(List.of(opencodeAdapter)));

        ResponseEntity<Map<String, Object>> response = controller.getProviderHealth("opencode");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody())
                .containsEntry("providerId", "opencode")
                .containsEntry("healthy", false);
    }

    @Test
    void health_returnsNotFoundForUnknownProvider() {
        AdkProviderController controller = controller(
                catalogOf("opencode"), adaptersOf("opencode"));

        ResponseEntity<Map<String, Object>> response = controller.getProviderHealth("nope");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void health_returnsNotFoundForACatalogEntryWithoutAnAdapter() {
        // The catalog names the core but no adapter serves it: admission refuses it, so
        // health must not claim a state it cannot probe.
        AdkProviderController controller = controller(
                catalogOf("opencode", "ghost-core"), adaptersOf("opencode"));

        ResponseEntity<Map<String, Object>> response = controller.getProviderHealth("ghost-core");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
