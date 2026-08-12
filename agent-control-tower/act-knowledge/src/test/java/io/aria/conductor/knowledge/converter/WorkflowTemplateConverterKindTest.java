package io.aria.conductor.knowledge.converter;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.common.model.WorkflowStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowTemplateConverterKindTest {

    @Mock
    AgentRepository agentRepository;

    private WorkflowTemplateConverter converter;

    @BeforeEach
    void setUp() {
        converter = new WorkflowTemplateConverter(agentRepository);
    }

    // ---- helpers ----

    private WorkflowChain chain(String name) {
        return WorkflowChain.builder()
                .id(UUID.randomUUID())
                .name(name)
                .description("Test workflow description")
                .status(WorkflowChain.Status.PENDING)
                .currentStepIndex(0)
                .createdAt(Instant.now())
                .build();
    }

    // ==================== yaml -> steps kind parsing ====================

    @Test
    void yamlToWorkflowSteps_parsesKindCaseInsensitive_andDefaultsGeneric() {
        Agent baAgent = Agent.builder().id(UUID.randomUUID()).role("ba").build();
        Agent devAgent = Agent.builder().id(UUID.randomUUID()).role("dev").build();
        when(agentRepository.findByRole("ba")).thenReturn(List.of(baAgent));
        when(agentRepository.findByRole("dev")).thenReturn(List.of(devAgent));

        String yaml = "steps:\n  - kind: ba\n    agent_role: ba\n    prompt_template: \"write spec\"\n"
                + "  - agent_role: dev\n    prompt_template: \"implement\"\n";
        List<WorkflowStep> steps = converter.yamlToWorkflowSteps(yaml);
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).getKind()).isEqualTo(WorkflowStep.StepKind.BA);
        assertThat(steps.get(1).getKind()).isEqualTo(WorkflowStep.StepKind.GENERIC);
    }

    // ==================== steps -> yaml kind emission ====================

    @Test
    void workflowChainToYaml_emitsKind() {
        UUID agentId = UUID.randomUUID();
        when(agentRepository.findById(agentId))
                .thenReturn(Optional.of(Agent.builder().id(agentId).role("ba").build()));

        WorkflowStep ba = WorkflowStep.builder().agentId(agentId)
                .kind(WorkflowStep.StepKind.BA).promptTemplate("p").build();
        String yaml = converter.workflowChainToYaml(chain("wf"), List.of(ba), null);
        assertThat(yaml).contains("kind: BA");
    }
}
