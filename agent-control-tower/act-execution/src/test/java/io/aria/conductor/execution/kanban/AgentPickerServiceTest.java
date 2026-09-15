package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.eligibility.AgentPickupEligibility;
import io.aria.conductor.common.exception.PickupRejectedException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentPickerServiceTest {

    private AgentPickerService.Candidates pool(AgentPickerService.Candidate... candidates) {
        AgentPickerService.Candidates pool = mock(AgentPickerService.Candidates.class);
        when(pool.eligible()).thenReturn(List.of(candidates));
        when(pool.excluded()).thenReturn(List.of());
        return pool;
    }

    @Test
    void prefersAgentMatchingTemplateLabel() {
        AgentPickerService.Choice choice = new AgentPickerService(pool(
                new AgentPickerService.Candidate(UUID.randomUUID(), "dev-worker", null),
                new AgentPickerService.Candidate(UUID.randomUUID(), "ba-agent", null)))
                .pick("ba-agent", "write spec", null);

        assertThat(choice.agentName()).isEqualTo("ba-agent");
    }

    @Test
    void templateIdMatchesByRoleFirst() {
        // The new-task modal sends template ids ("ba" | "dev" | "qa"), which are
        // role keys of the deployed agents — not name fragments. Name containment
        // can never find "Business Analyst Agent" from "ba".
        UUID baId = UUID.randomUUID();
        AgentPickerService.Choice choice = new AgentPickerService(pool(
                new AgentPickerService.Candidate(UUID.randomUUID(), "Developer Agent", "dev"),
                new AgentPickerService.Candidate(baId, "Business Analyst Agent", "ba"),
                new AgentPickerService.Candidate(UUID.randomUUID(), "Abacus Worker", null)))
                .pick("ba", "write spec", null);

        assertThat(choice.agentId()).isEqualTo(baId);
        assertThat(choice.agentName()).isEqualTo("Business Analyst Agent");
    }

    @Test
    void exactNameMatchWinsOverRoleMatch() {
        // An agent actually named like the template beats a role-only match.
        UUID exactId = UUID.randomUUID();
        AgentPickerService.Choice choice = new AgentPickerService(pool(
                new AgentPickerService.Candidate(UUID.randomUUID(), "Other Worker", "ba"),
                new AgentPickerService.Candidate(exactId, "BA", null)))
                .pick("ba", "write spec", null);

        assertThat(choice.agentId()).isEqualTo(exactId);
    }

    @Test
    void fallsBackToFirstEligibleAgent() {
        AgentPickerService.Choice choice = new AgentPickerService(pool(
                new AgentPickerService.Candidate(UUID.randomUUID(), "dev-worker", null)))
                .pick(null, "anything", null);

        assertThat(choice.agentName()).isEqualTo("dev-worker");
    }

    @Test
    void fallsBackWhenNothingMatches() {
        AgentPickerService.Choice choice = new AgentPickerService(pool(
                new AgentPickerService.Candidate(UUID.randomUUID(), "dev-worker", null)))
                .pick("nonexistent-template", "anything", null);

        assertThat(choice.agentName()).isEqualTo("dev-worker");
    }

    @Test
    void emptyPoolRejectsWithNoEligibleAgentAndNamesTheExcluded() {
        AgentPickerService service = new AgentPickerService(new AgentPickerService.Candidates() {
            @Override
            public List<AgentPickerService.Candidate> eligible() {
                return List.of();
            }

            @Override
            public List<AgentPickerService.Excluded> excluded() {
                return List.of(new AgentPickerService.Excluded("Aria",
                        List.of(AgentPickupEligibility.Reason.RESERVED_OPERATOR_AGENT)));
            }
        });

        assertThatThrownBy(() -> service.pick(null, "title", "desc"))
                .isInstanceOf(PickupRejectedException.class)
                .satisfies(e -> {
                    PickupRejectedException rejected = (PickupRejectedException) e;
                    assertThat(rejected.code()).isEqualTo("NO_ELIGIBLE_AGENT");
                    assertThat(rejected.details()).containsEntry("evaluated", 1);
                    assertThat(rejected.details().get("excluded").toString())
                            .contains("RESERVED_OPERATOR_AGENT");
                });
    }
}
