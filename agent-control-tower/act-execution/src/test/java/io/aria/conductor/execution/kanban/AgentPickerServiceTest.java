package io.aria.conductor.execution.kanban;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentPickerServiceTest {

    private AgentPickerService.Candidates pool(AgentPickerService.Candidate... candidates) {
        AgentPickerService.Candidates candidates1 = mock(AgentPickerService.Candidates.class);
        when(candidates1.healthy()).thenReturn(List.of(candidates));
        return candidates1;
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
    void fallsBackToFirstHealthyAgent() {
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
    void emptyPoolThrows() {
        AgentPickerService.Candidates candidates = mock(AgentPickerService.Candidates.class);
        when(candidates.healthy()).thenReturn(List.of());
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new AgentPickerService(candidates).pick(null, "t", null))
                .isInstanceOf(IllegalStateException.class);
    }
}
