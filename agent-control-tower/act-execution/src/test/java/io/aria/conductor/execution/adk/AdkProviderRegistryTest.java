package io.aria.conductor.execution.adk;

import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.execution.runtime.CoreCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdkProviderRegistryTest {

    @Mock AdkProvider mockProviderA;
    @Mock AdkProvider mockProviderB;
    /** The production opencode provider's registry identity, as the existing Mockito style here. */
    @Mock AdkProvider opencodeProvider;
    @Mock Agent agent;

    AdkSystemProperties systemProperties;

    @BeforeEach
    void setUp() {
        lenient().when(mockProviderA.providerId()).thenReturn("mock-a");
        lenient().when(mockProviderB.providerId()).thenReturn("mock-b");
        lenient().when(opencodeProvider.providerId()).thenReturn("opencode");
        systemProperties = new AdkSystemProperties();
        systemProperties.setDefaultProvider("mock-a");
    }

    @Test
    void resolve_usesAgentAdkProvider_whenSet() {
        when(agent.getAdkProvider()).thenReturn("mock-b");
        AdkProviderRegistry registry = new AdkProviderRegistry(List.of(mockProviderA, mockProviderB), systemProperties);
        assertThat(registry.resolve(agent)).isSameAs(mockProviderB);
    }

    @Test
    void resolve_fallsBackToDefault_whenAgentAdkProviderIsNull() {
        when(agent.getAdkProvider()).thenReturn(null);
        AdkProviderRegistry registry = new AdkProviderRegistry(List.of(mockProviderA, mockProviderB), systemProperties);
        assertThat(registry.resolve(agent)).isSameAs(mockProviderA);
    }

    @Test
    void resolve_fallsBackToDefault_whenAgentAdkProviderIsBlank() {
        when(agent.getAdkProvider()).thenReturn("   ");
        AdkProviderRegistry registry = new AdkProviderRegistry(List.of(mockProviderA, mockProviderB), systemProperties);
        assertThat(registry.resolve(agent)).isSameAs(mockProviderA);
    }

    @Test
    void anUnknownConfiguredDefaultFailsClosed() {
        AdkSystemProperties props = new AdkSystemProperties();
        props.setDefaultProvider("langchain");
        assertThatThrownBy(() -> new AdkProviderRegistry(List.of(opencodeProvider), props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported ADK provider");
    }

    @Test
    void resolve_throwsOnUnknownProviderSelection_insteadOfFallingBack() {
        when(agent.getAdkProvider()).thenReturn("nonexistent");
        AdkProviderRegistry registry = new AdkProviderRegistry(List.of(mockProviderA, mockProviderB), systemProperties);
        assertThatThrownBy(() -> registry.resolve(agent))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported ADK provider");
    }

    @Test
    void constructor_throws_whenDefaultProviderMissing() {
        systemProperties.setDefaultProvider("nonexistent");
        assertThatThrownBy(() -> new AdkProviderRegistry(List.of(mockProviderA, mockProviderB), systemProperties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported ADK provider");
    }

    @Test
    void constructor_throws_whenNoProvidersRegistered() {
        assertThatThrownBy(() -> new AdkProviderRegistry(Collections.emptyList(), systemProperties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No ADK providers registered");
    }

    @Test
    void getProviderIds_returnsAllRegisteredIds() {
        AdkProviderRegistry registry = new AdkProviderRegistry(List.of(mockProviderA, mockProviderB), systemProperties);
        assertThat(registry.getProviderIds()).containsExactlyInAnyOrder("mock-a", "mock-b");
    }

    @Test
    void getProvider_returnsCorrectProvider() {
        AdkProviderRegistry registry = new AdkProviderRegistry(List.of(mockProviderA, mockProviderB), systemProperties);
        assertThat(registry.getProvider("mock-a")).isSameAs(mockProviderA);
        assertThat(registry.getProvider("mock-b")).isSameAs(mockProviderB);
        assertThat(registry.getProvider("nonexistent")).isNull();
    }

    @Test
    void resolve_withSingleProvider_works() {
        when(agent.getAdkProvider()).thenReturn("mock-a");
        AdkProviderRegistry registry = new AdkProviderRegistry(List.of(mockProviderA), systemProperties);
        assertThat(registry.resolve(agent)).isSameAs(mockProviderA);
    }

    // ------------------------------------------------ production catalog cores (Task 20 fix round 1)

    /** The production core catalog: exactly the two supported cores, both modes. */
    private static CoreCatalog productionCatalog() {
        Map<String, Set<ExecutionMode>> modes = new LinkedHashMap<>();
        modes.put("qoder", EnumSet.of(ExecutionMode.HOST, ExecutionMode.SANDBOX));
        modes.put("opencode", EnumSet.of(ExecutionMode.HOST, ExecutionMode.SANDBOX));
        return new CoreCatalog(modes);
    }

    /**
     * The exact Host start path of the defect: the configured default is the
     * Host-capable catalog core {@code qoder}, but the only provider bean is
     * {@code opencode}. The registry must consult the catalog instead of demanding
     * a legacy provider bean, because the run path routes catalog cores to the run
     * coordinator through the core launcher -- and it must boot.
     */
    @Test
    void aCatalogConfiguredDefaultBoots_withoutAProviderBean() {
        AdkSystemProperties props = new AdkSystemProperties();
        props.setDefaultProvider("qoder");

        AdkProviderRegistry registry = new AdkProviderRegistry(List.of(opencodeProvider), props,
                productionCatalog());

        assertThat(registry.getProviderIds()).containsExactly("opencode");
        assertThat(registry.defaultProviderId()).isEqualTo("qoder");
    }

    /**
     * A qoder agent resolves through the catalog path: the registry demands no
     * legacy provider bean for a catalog core (its run-owned runtime is opened by
     * the core launcher/coordinator), and it never substitutes another provider.
     */
    @Test
    void aQoderAgentResolvesThroughTheCatalogPath_withoutAProviderBean() {
        when(agent.getAdkProvider()).thenReturn("qoder");
        AdkSystemProperties props = new AdkSystemProperties();
        props.setDefaultProvider("opencode");

        AdkProviderRegistry registry = new AdkProviderRegistry(List.of(opencodeProvider), props,
                productionCatalog());

        assertThat(registry.resolve(agent))
                .as("a catalog core is served by the run-owned core path, not by a provider bean")
                .isNull();
    }

    /** An agent that stores no core inherits the catalog default, which is likewise not a provider bean. */
    @Test
    void anAgentWithoutAStoredCoreResolvesToTheCatalogDefault() {
        when(agent.getAdkProvider()).thenReturn(null);
        AdkSystemProperties props = new AdkSystemProperties();
        props.setDefaultProvider("qoder");

        AdkProviderRegistry registry = new AdkProviderRegistry(List.of(opencodeProvider), props,
                productionCatalog());

        assertThat(registry.resolve(agent)).isNull();
    }

    /** An unknown default still fails closed with the exact refusal, catalog injected or not. */
    @Test
    void anUnknownConfiguredDefaultStillFailsClosedWithTheExactRefusal() {
        AdkSystemProperties props = new AdkSystemProperties();
        props.setDefaultProvider("langchain");

        assertThatThrownBy(() -> new AdkProviderRegistry(List.of(opencodeProvider), props, productionCatalog()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unsupported ADK provider: configured default 'langchain' is not one of the"
                        + " registered providers [opencode]; the supported cores are exactly the registered ones"
                        + " and there is no fallback");
    }

    /** A core outside the catalog is still refused by name, with the exact no-fallback message. */
    @Test
    void anUnknownAgentCoreStillFailsClosedWithTheExactRefusal() {
        java.util.UUID agentId = java.util.UUID.randomUUID();
        when(agent.getAdkProvider()).thenReturn("langchain");
        when(agent.getId()).thenReturn(agentId);
        AdkSystemProperties props = new AdkSystemProperties();
        props.setDefaultProvider("opencode");

        AdkProviderRegistry registry = new AdkProviderRegistry(List.of(opencodeProvider), props,
                productionCatalog());

        assertThatThrownBy(() -> registry.resolve(agent))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unsupported ADK provider 'langchain' for agent " + agentId
                        + "; registered providers: [opencode] (there is no fallback)");
    }
}
