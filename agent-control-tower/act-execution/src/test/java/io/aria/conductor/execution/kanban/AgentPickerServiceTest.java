package io.aria.conductor.execution.kanban;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentPickerServiceTest {

    @Test
    void prefersAgentMatchingTemplateLabel() {
        AgentPickerService.Candidates candidates = mock(AgentPickerService.Candidates.class);
        when(candidates.healthy()).thenReturn(List.of(
                new AgentPickerService.Candidate(UUID.randomUUID(), "dev-worker"),
                new AgentPickerService.Candidate(UUID.randomUUID(), "ba-agent")));

        AgentPickerService.Choice choice = new AgentPickerService(candidates)
                .pick("ba-agent", "write spec", null);

        assertThat(choice.agentName()).isEqualTo("ba-agent");
    }

    @Test
    void fallsBackToFirstHealthyAgent() {
        AgentPickerService.Candidates candidates = mock(AgentPickerService.Candidates.class);
        when(candidates.healthy()).thenReturn(List.of(
                new AgentPickerService.Candidate(UUID.randomUUID(), "dev-worker")));

        AgentPickerService.Choice choice = new AgentPickerService(candidates).pick(null, "anything", null);

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
