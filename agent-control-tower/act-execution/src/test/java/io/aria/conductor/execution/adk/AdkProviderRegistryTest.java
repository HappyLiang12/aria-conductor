package io.aria.conductor.execution.adk;

import io.aria.conductor.common.model.Agent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdkProviderRegistryTest {

    @Mock AdkProvider mockProviderA;
    @Mock AdkProvider mockProviderB;
    @Mock Agent agent;

    AdkSystemProperties systemProperties;

    @BeforeEach
    void setUp() {
        lenient().when(mockProviderA.providerId()).thenReturn("mock-a");
        lenient().when(mockProviderB.providerId()).thenReturn("mock-b");
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
    void resolve_fallsBackToDefault_whenUnknownProvider() {
        when(agent.getAdkProvider()).thenReturn("nonexistent");
        AdkProviderRegistry registry = new AdkProviderRegistry(List.of(mockProviderA, mockProviderB), systemProperties);
        assertThat(registry.resolve(agent)).isSameAs(mockProviderA);
    }

    @Test
    void constructor_fallsBackToFirstProvider_whenDefaultProviderMissing() {
        systemProperties.setDefaultProvider("nonexistent");
        AdkProviderRegistry registry = new AdkProviderRegistry(List.of(mockProviderA, mockProviderB), systemProperties);
        assertThat(registry.getProviderIds()).containsExactlyInAnyOrder("mock-a", "mock-b");
        when(agent.getAdkProvider()).thenReturn(null);
        assertThat(registry.resolve(agent)).isSameAs(mockProviderA);
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
}
