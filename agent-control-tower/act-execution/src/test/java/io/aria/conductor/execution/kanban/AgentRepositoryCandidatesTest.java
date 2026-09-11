package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.common.AriaConstants;
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
 * the operator assistant (Aria, identified by its reserved id, not by its null
 * model) or to a mock-model test agent makes the run "complete" instantly with
 * no real work — the operator sees a card jump to done and no agent activity.
 * Model-less agents that are NOT the assistant stay eligible: NATIVE agents
 * resolve the platform default LLM provider at run time.
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
    void excludesAssistantByIdentityAndMockAgents() {
        when(agentRepository.findByHealthStatusNot(HealthStatus.RETIRED)).thenReturn(List.of(
                agent(AriaConstants.ARIA_AGENT_ID.toString(), "Aria", null, HealthStatus.HEALTHY),
                agent("00000000-0000-0000-0000-0000000000aa", "SDD BA Agent", "mock", HealthStatus.HEALTHY),
                agent("00000000-0000-0000-0000-0000000000bb", "SDD DEV Agent", "MOCK", HealthStatus.HEALTHY),
                agent("00000000-0000-0000-0000-0000000000cc", "Developer Agent", "ali-copilot", HealthStatus.HEALTHY)));

        List<AgentPickerService.Candidate> pool = candidates.healthy();

        assertThat(pool).extracting(AgentPickerService.Candidate::name)
                .containsExactly("Developer Agent");
    }

    @Test
    void keepsModelLessWorkersThatAreNotTheAssistant() {
        // Regression guard (CI E2E shard 2): NATIVE agents are created without a
        // model and resolve the platform default LLM provider at run time, so a
        // model-less worker must stay pickable. Only the reserved assistant is
        // excluded by identity.
        when(agentRepository.findByHealthStatusNot(HealthStatus.RETIRED)).thenReturn(List.of(
                agent("00000000-0000-0000-0000-0000000000ff", "e2e-agent-123", null, HealthStatus.HEALTHY)));

        assertThat(candidates.healthy()).extracting(AgentPickerService.Candidate::name)
                .containsExactly("e2e-agent-123");
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
