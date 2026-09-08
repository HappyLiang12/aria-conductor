package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.common.model.HealthStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Production {@link AgentPickerService.Candidates} adapter: non-retired agents
 * from {@link AgentRepository} (DEGRADED/UNHEALTHY agents stay eligible so a
 * degraded agent is still better than no pickup).
 */
@Component
public class AgentRepositoryCandidates implements AgentPickerService.Candidates {

    private final AgentRepository agentRepository;

    public AgentRepositoryCandidates(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    @Override
    public List<AgentPickerService.Candidate> healthy() {
        return agentRepository.findByHealthStatusNot(HealthStatus.RETIRED).stream()
                .map(a -> new AgentPickerService.Candidate(a.getId(), a.getName()))
                .toList();
    }
}
