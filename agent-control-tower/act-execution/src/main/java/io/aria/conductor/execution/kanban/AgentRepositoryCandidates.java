package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.eligibility.AgentPickupEligibility;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.HealthStatus;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Production {@link AgentPickerService.Candidates} adapter. The pool and the
 * excluded reasons both come from {@link AgentPickupEligibility}, so the picker
 * and the operator-visible rejection message can never disagree.
 */
@Component
public class AgentRepositoryCandidates implements AgentPickerService.Candidates {

    private final AgentRepository agentRepository;
    private final AgentPickupEligibility eligibility;

    public AgentRepositoryCandidates(AgentRepository agentRepository, AgentPickupEligibility eligibility) {
        this.agentRepository = agentRepository;
        this.eligibility = eligibility;
    }

    @Override
    public List<AgentPickerService.Candidate> eligible() {
        return candidates().stream()
                .filter(c -> c.evaluation().eligible())
                .map(c -> new AgentPickerService.Candidate(
                        c.agent().getId(), c.agent().getName(), c.agent().getRole()))
                .toList();
    }

    @Override
    public List<AgentPickerService.Excluded> excluded() {
        return candidates().stream()
                .filter(c -> !c.evaluation().eligible())
                .map(c -> new AgentPickerService.Excluded(
                        c.agent().getName(), c.evaluation().reasons()))
                .toList();
    }

    /**
     * Deterministic pool order: "first eligible agent" must not depend on the
     * database's plan for an unordered query.
     */
    private List<Evaluated> candidates() {
        return agentRepository.findByHealthStatusNot(HealthStatus.RETIRED).stream()
                .sorted(Comparator.comparing(Agent::getName, Comparator.nullsLast(String::compareTo)))
                .map(a -> new Evaluated(a, eligibility.evaluate(a)))
                .toList();
    }

    private record Evaluated(Agent agent, AgentPickupEligibility.Evaluation evaluation) {}
}
