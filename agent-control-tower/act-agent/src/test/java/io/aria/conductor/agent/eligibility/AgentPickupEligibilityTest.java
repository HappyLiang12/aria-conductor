package io.aria.conductor.agent.eligibility;

import io.aria.conductor.agent.eligibility.AgentPickupEligibility.Evaluation;
import io.aria.conductor.agent.eligibility.AgentPickupEligibility.Reason;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.HealthStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Truth table for the single pickup-eligibility authority. Every dispatch and
 * every eligibility message reads this, so the table is the specification.
 */
class AgentPickupEligibilityTest {

    private final AgentPickupEligibility eligibility = new AgentPickupEligibility();

    private static final UUID WORKER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private Agent agent(UUID id, Boolean pickupEnabled, HealthStatus health) {
        return Agent.builder().id(id).name("worker").agentType(io.aria.conductor.common.model.AgentType.NATIVE)
                .pickupEnabled(pickupEnabled).healthStatus(health).build();
    }

    @Test
    void healthyEnabledWorkerIsEligible() {
        Evaluation result = eligibility.evaluate(agent(WORKER, Boolean.TRUE, HealthStatus.HEALTHY));

        assertThat(result.eligible()).isTrue();
        assertThat(result.reasons()).isEmpty();
    }

    @Test
    void nullPickupEnabledCountsAsEnabled() {
        // Rows written before V56 and NATIVE agents built without the flag must
        // stay pickable; only an explicit FALSE disables an agent.
        assertThat(eligibility.evaluate(agent(WORKER, null, HealthStatus.HEALTHY)).eligible()).isTrue();
    }

    @Test
    void degradedWorkerIsEligible() {
        assertThat(eligibility.evaluate(agent(WORKER, Boolean.TRUE, HealthStatus.DEGRADED)).eligible()).isTrue();
    }

    @Test
    void reservedOperatorAgentIsExcludedByIdentityRegardlessOfHealth() {
        Evaluation result = eligibility.evaluate(
                agent(AriaConstants.ARIA_AGENT_ID, Boolean.TRUE, HealthStatus.HEALTHY));

        assertThat(result.eligible()).isFalse();
        assertThat(result.reasons()).containsExactly(Reason.RESERVED_OPERATOR_AGENT);
    }

    @Test
    void retiredWorkerIsExcluded() {
        Evaluation result = eligibility.evaluate(agent(WORKER, Boolean.TRUE, HealthStatus.RETIRED));

        assertThat(result.eligible()).isFalse();
        assertThat(result.reasons()).containsExactly(Reason.RETIRED);
    }

    @Test
    void unhealthyWorkerIsExcluded() {
        Evaluation result = eligibility.evaluate(agent(WORKER, Boolean.TRUE, HealthStatus.UNHEALTHY));

        assertThat(result.eligible()).isFalse();
        assertThat(result.reasons()).containsExactly(Reason.UNHEALTHY);
    }

    @Test
    void disabledWorkerIsExcluded() {
        Evaluation result = eligibility.evaluate(agent(WORKER, Boolean.FALSE, HealthStatus.HEALTHY));

        assertThat(result.eligible()).isFalse();
        assertThat(result.reasons()).containsExactly(Reason.PICKUP_DISABLED);
    }

    @Test
    void reasonsAccumulateInStableOrder() {
        Evaluation result = eligibility.evaluate(agent(WORKER, Boolean.FALSE, HealthStatus.RETIRED));

        assertThat(result.eligible()).isFalse();
        assertThat(result.reasons()).containsExactly(Reason.PICKUP_DISABLED, Reason.RETIRED);
    }
}
