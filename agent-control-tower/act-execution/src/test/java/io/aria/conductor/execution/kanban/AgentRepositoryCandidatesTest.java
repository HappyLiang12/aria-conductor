package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.HealthStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Defect D2: the pickup pool must contain real workers only. Handing a card to
 * the operator assistant (Aria, no model) or to a mock-model test agent makes
 * the run "complete" instantly with no real work — the operator sees a card
 * jump to done and no agent activity.
 */
class AgentRepositoryCandidatesTest {

    private AgentRepository agentRepository;
    private AgentRepositoryCandidates candidates;

    @BeforeEach
    void setUp() {
        agentRepository = mock(AgentRepository.class);
        candidates = new AgentRepositoryCandidates(agentRepository);
    }

    private Agent agent(String id, String name, String model, HealthStatus health) {
        return Agent.builder().id(java.util.UUID.fromString(id)).name(name)
                .model(model).healthStatus(health).build();
    }

    @Test
    void excludesModelLessAssistantAndMockAgents() {
        when(agentRepository.findByHealthStatusNot(HealthStatus.RETIRED)).thenReturn(List.of(
                agent("00000000-0000-0000-0000-000000000001", "Aria", null, HealthStatus.HEALTHY),
                agent("00000000-0000-0000-0000-0000000000aa", "SDD BA Agent", "mock", HealthStatus.HEALTHY),
                agent("00000000-0000-0000-0000-0000000000bb", "SDD DEV Agent", "MOCK", HealthStatus.HEALTHY),
                agent("00000000-0000-0000-0000-0000000000cc", "Developer Agent", "ali-copilot", HealthStatus.HEALTHY)));

        List<AgentPickerService.Candidate> pool = candidates.healthy();

        assertThat(pool).extracting(AgentPickerService.Candidate::name)
                .containsExactly("Developer Agent");
    }

    @Test
    void keepsDegradedWorkersButDropsUnhealthyOnes() {
        when(agentRepository.findByHealthStatusNot(HealthStatus.RETIRED)).thenReturn(List.of(
                agent("00000000-0000-0000-0000-0000000000dd", "Degraded Worker", "ali-copilot", HealthStatus.DEGRADED),
                agent("00000000-0000-0000-0000-0000000000ee", "Unhealthy Worker", "ali-copilot", HealthStatus.UNHEALTHY)));

        assertThat(candidates.healthy()).extracting(AgentPickerService.Candidate::name)
                .containsExactly("Degraded Worker");
    }
}
