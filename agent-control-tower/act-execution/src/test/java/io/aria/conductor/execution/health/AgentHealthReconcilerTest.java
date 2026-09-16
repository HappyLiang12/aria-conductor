package io.aria.conductor.execution.health;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.execution.adk.AdkProvider;
import io.aria.conductor.execution.adk.AdkProviderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentHealthReconcilerTest {

    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private AgentRepository agentRepository;
    private AdkProviderRegistry registry;
    private AdkProvider provider;
    private AgentHealthReconciler reconciler;

    @BeforeEach
    void setUp() {
        agentRepository = mock(AgentRepository.class);
        registry = mock(AdkProviderRegistry.class);
        provider = mock(AdkProvider.class);
        when(registry.resolve(any())).thenReturn(provider);
        MockEnvironment environment = new MockEnvironment();
        reconciler = new AgentHealthReconciler(agentRepository, registry, environment);
    }

    private Agent agent(HealthStatus health) {
        return Agent.builder().id(AGENT).name("worker").agentType(AgentType.NATIVE).healthStatus(health).build();
    }

    @Test
    void reachableStampsHealthy() {
        when(agentRepository.findByHealthStatusNot(HealthStatus.RETIRED)).thenReturn(List.of(agent(HealthStatus.UNHEALTHY)));
        when(provider.probeRuntimeHealth(AGENT)).thenReturn(AdkProvider.RuntimeHealth.REACHABLE);

        reconciler.reconcile();

        verify(agentRepository).reconcileHealth(eq(AGENT), eq(HealthStatus.HEALTHY), any());
    }

    @Test
    void unreachableStampsUnhealthy() {
        when(agentRepository.findByHealthStatusNot(HealthStatus.RETIRED)).thenReturn(List.of(agent(HealthStatus.HEALTHY)));
        when(provider.probeRuntimeHealth(AGENT)).thenReturn(AdkProvider.RuntimeHealth.UNREACHABLE);

        reconciler.reconcile();

        verify(agentRepository).reconcileHealth(eq(AGENT), eq(HealthStatus.UNHEALTHY), any());
    }

    @Test
    void notStartedLeavesTheStampAlone() {
        // The regression this reconciler must never cause: RunService.createRun and
        // kanban pickup both reject UNHEALTHY, so stamping an agent that has simply
        // never run would break dispatch on a fresh install.
        when(agentRepository.findByHealthStatusNot(HealthStatus.RETIRED)).thenReturn(List.of(agent(HealthStatus.HEALTHY)));
        when(provider.probeRuntimeHealth(AGENT)).thenReturn(AdkProvider.RuntimeHealth.NOT_STARTED);

        reconciler.reconcile();

        verify(agentRepository, never()).reconcileHealth(any(), any(), any());
    }

    @Test
    void aNullProbeIsTreatedAsNotStarted() {
        // A provider that cannot report runtime health is indistinguishable from
        // one that reports NOT_STARTED — and stamping UNHEALTHY for it would break
        // dispatch on installs that worked before. Mockito does not run interface
        // default methods, so an unstubbed mock returns null rather than
        // NOT_STARTED: exactly this "provider cannot answer" shape.
        when(agentRepository.findByHealthStatusNot(HealthStatus.RETIRED)).thenReturn(List.of(agent(HealthStatus.HEALTHY)));

        reconciler.reconcile();

        verify(agentRepository, never()).reconcileHealth(any(), any(), any());
    }

    @Test
    void aFailingProbeDoesNotAbortTheSweep() {
        Agent second = Agent.builder().id(UUID.randomUUID()).name("other").agentType(AgentType.NATIVE)
                .healthStatus(HealthStatus.HEALTHY).build();
        when(agentRepository.findByHealthStatusNot(HealthStatus.RETIRED)).thenReturn(List.of(agent(HealthStatus.HEALTHY), second));
        when(provider.probeRuntimeHealth(AGENT)).thenThrow(new RuntimeException("boom"));
        when(provider.probeRuntimeHealth(second.getId())).thenReturn(AdkProvider.RuntimeHealth.REACHABLE);

        reconciler.reconcile();

        // The exploded agent is skipped, not stamped UNHEALTHY by default.
        verify(agentRepository, never()).reconcileHealth(eq(AGENT), any(), any());
        verify(agentRepository).reconcileHealth(eq(second.getId()), eq(HealthStatus.HEALTHY), any());
    }
}
