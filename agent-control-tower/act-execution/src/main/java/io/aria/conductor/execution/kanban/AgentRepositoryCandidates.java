package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.HealthStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Production {@link AgentPickerService.Candidates} adapter: agents from
 * {@link AgentRepository} whose health is HEALTHY or DEGRADED. UNHEALTHY is
 * excluded too — RunService.createRun rejects it, so picking an unhealthy
 * agent only guarantees a failed pickup — while a degraded agent is still
 * better than no pickup.
 *
 * <p>Defect D2: the pool must contain real workers only. The reserved Aria
 * operator assistant (excluded by {@link AriaConstants#ARIA_AGENT_ID}, not by
 * its null model — NATIVE workers are created model-less and resolve the
 * platform default LLM provider at run time) and `mock`-model agents
 * (seeded/test workers returning canned responses) would "complete" a run
 * instantly without doing any work, so an unassigned card must never be handed
 * to them.
 */
@Component
public class AgentRepositoryCandidates implements AgentPickerService.Candidates {

    /** Model value used by seeded/test agents that return canned responses. */
    private static final String MOCK_MODEL = "mock";

    private final AgentRepository agentRepository;

    public AgentRepositoryCandidates(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    @Override
    public List<AgentPickerService.Candidate> healthy() {
        return agentRepository.findByHealthStatusNot(HealthStatus.RETIRED).stream()
                .filter(a -> a.getHealthStatus() == HealthStatus.HEALTHY
                          || a.getHealthStatus() == HealthStatus.DEGRADED)
                .filter(AgentRepositoryCandidates::isRealWorker)
                // Deterministic pool order: "first healthy agent" must not depend
                // on the DB's plan for an unordered query.
                .sorted(java.util.Comparator.comparing(
                        Agent::getName, java.util.Comparator.nullsLast(String::compareTo)))
                .map(a -> new AgentPickerService.Candidate(a.getId(), a.getName(), a.getRole()))
                .toList();
    }

    private static boolean isRealWorker(Agent agent) {
        if (AriaConstants.ARIA_AGENT_ID.equals(agent.getId())) {
            return false;
        }
        String model = agent.getModel();
        return model == null || !MOCK_MODEL.equalsIgnoreCase(model.trim());
    }
}
