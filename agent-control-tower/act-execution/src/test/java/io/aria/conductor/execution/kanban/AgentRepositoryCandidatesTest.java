package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.eligibility.AgentPickupEligibility;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HealthStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The pool is whatever the single eligibility authority says is eligible, and
 * the excluded agents are reported with their reason so a rejection can name
 * them. The former model='mock' heuristic is gone: it rested on the false
 * premise that a mock model produces canned responses.
 */
class AgentRepositoryCandidatesTest {

    private AgentRepository agentRepository;
    private AgentRepositoryCandidates candidates;

    @BeforeEach
    void setUp() {
        agentRepository = mock(AgentRepository.class);
        candidates = new AgentRepositoryCandidates(agentRepository, new AgentPickupEligibility());
    }

    private static Agent agent(UUID id, String name, String model, HealthStatus health, Boolean pickupEnabled) {
        return Agent.builder().id(id).name(name).agentType(AgentType.NATIVE).model(model)
                .healthStatus(health).pickupEnabled(pickupEnabled).build();
    }

    private static final UUID SEEDED_BA = UUID.fromString("ba000000-0000-0000-0000-000000000001");
    private static final UUID WORKER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Test
    void excludedAgentsAreReportedWithTheirReason() {
        when(agentRepository.findByHealthStatusNot(HealthStatus.RETIRED)).thenReturn(List.of(
                agent(AriaConstants.ARIA_AGENT_ID, "Aria", null, HealthStatus.HEALTHY, Boolean.TRUE),
                agent(WORKER, "Disabled Worker", "ali-copilot", HealthStatus.HEALTHY, Boolean.FALSE)));

        assertThat(candidates.eligible()).isEmpty();
        assertThat(candidates.excluded()).extracting(AgentPickerService.Excluded::name)
                .containsExactlyInAnyOrder("Aria", "Disabled Worker");
        assertThat(candidates.excluded()).extracting(AgentPickerService.Excluded::reasons)
                .containsExactlyInAnyOrder(
                        List.of(AgentPickupEligibility.Reason.RESERVED_OPERATOR_AGENT),
                        List.of(AgentPickupEligibility.Reason.PICKUP_DISABLED));
    }

    @Test
    void seededMockModelAgentsAreNowEligible() {
        when(agentRepository.findByHealthStatusNot(HealthStatus.RETIRED)).thenReturn(List.of(
                agent(SEEDED_BA, "SDD BA Agent", "mock", HealthStatus.HEALTHY, Boolean.TRUE)));

        assertThat(candidates.eligible()).extracting(AgentPickerService.Candidate::name)
                .containsExactly("SDD BA Agent");
    }

    @Test
    void poolIsOrderedByName() {
        when(agentRepository.findByHealthStatusNot(HealthStatus.RETIRED)).thenReturn(List.of(
                agent(WORKER, "Zeta", null, HealthStatus.HEALTHY, Boolean.TRUE),
                agent(SEEDED_BA, "Alpha", null, HealthStatus.HEALTHY, Boolean.TRUE)));

        assertThat(candidates.eligible()).extracting(AgentPickerService.Candidate::name)
                .containsExactly("Alpha", "Zeta");
    }
}
