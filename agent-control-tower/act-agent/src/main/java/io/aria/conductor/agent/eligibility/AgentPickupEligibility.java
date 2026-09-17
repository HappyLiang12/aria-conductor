package io.aria.conductor.agent.eligibility;

import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.HealthStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The single authority for "may this agent be handed a kanban card?".
 *
 * <p>Deliberately a pure function of the {@link Agent} record: no repository, no
 * runtime probe. Kanban pickup, the dispatch pre-check and the API/UI all read
 * this one evaluation, so the pool and the operator-visible reason can never
 * drift apart — the previous duplicate predicate did exactly that.
 *
 * <p>A {@code null} pickup flag counts as enabled: rows written before the
 * column existed and model-less NATIVE agents must stay pickable.
 */
@Component
public class AgentPickupEligibility {

    public enum Reason {
        RESERVED_OPERATOR_AGENT,
        PICKUP_DISABLED,
        RETIRED,
        UNHEALTHY
    }

    public record Evaluation(boolean eligible, List<Reason> reasons) {}

    public Evaluation evaluate(Agent agent) {
        List<Reason> reasons = new ArrayList<>();
        if (AriaConstants.ARIA_AGENT_ID.equals(agent.getId())) {
            reasons.add(Reason.RESERVED_OPERATOR_AGENT);
        }
        if (Boolean.FALSE.equals(agent.getPickupEnabled())) {
            reasons.add(Reason.PICKUP_DISABLED);
        }
        if (agent.getHealthStatus() == HealthStatus.RETIRED) {
            reasons.add(Reason.RETIRED);
        }
        if (agent.getHealthStatus() == HealthStatus.UNHEALTHY) {
            reasons.add(Reason.UNHEALTHY);
        }
        return new Evaluation(reasons.isEmpty(), List.copyOf(reasons));
    }
}
