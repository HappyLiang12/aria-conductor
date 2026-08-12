package io.aria.conductor.aria.tools.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.agent.dto.CreateWorkflowRequest;
import io.aria.conductor.agent.dto.WorkflowResponse;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.common.model.WorkflowStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Dual-format step parsing (JSON string / YAML), role-based agent
 * resolution, maxIterations defaulting and the read/cancel/retry tools of
 * {@link WorkflowToolHandler} that WorkflowToolHandlerTest leaves untested.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowToolHandlerEdgeCasesTest {

    @Mock private WorkflowService workflowService;
    @Mock private AgentRepository agentRepository;

    private WorkflowToolHandler handler;

    private final UUID devAgentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        handler = new WorkflowToolHandler(workflowService,
                org.mockito.Mockito.mock(io.aria.conductor.knowledge.service.WorkflowTemplateService.class),
                agentRepository, new ObjectMapper());
        lenient().when(agentRepository.findByName("dev-bot")).thenReturn(Optional.of(
                Agent.builder().id(devAgentId).name("dev-bot").build()));
    }

    private WorkflowResponse response(UUID id, WorkflowChain.Status status) {
        return WorkflowResponse.builder()
                .id(id).name("wf").status(status)
                .currentStepIndex(0).totalSteps(1)
                .build();
    }

    @Test
    void createWorkflow_acceptsStepsAsJsonArrayString() {
        UUID wfId = UUID.randomUUID();
        when(workflowService.createAndStart(any())).thenReturn(response(wfId, WorkflowChain.Status.RUNNING));

        String result = handler.execute(Map.of(
                "toolName", "create_workflow",
                "name", "pipeline",
                "steps", "[{\"agent\":\"dev-bot\",\"promptTemplate\":\"implement\"}]"));

        ArgumentCaptor<CreateWorkflowRequest> captor =
                ArgumentCaptor.forClass(CreateWorkflowRequest.class);
        verify(workflowService).createAndStart(captor.capture());
        assertThat(captor.getValue().getSteps()).hasSize(1);
        assertThat(captor.getValue().getSteps().get(0).getAgentId()).isEqualTo(devAgentId);
        assertThat(captor.getValue().getSteps().get(0).getPromptTemplate()).isEqualTo("implement");
        assertThat(result).contains("pipeline").contains(wfId.toString()).contains("RUNNING");
    }

    @Test
    void createWorkflow_acceptsYamlDefinitionWithStepsKey() {
        when(workflowService.createAndStart(any())).thenReturn(response(UUID.randomUUID(), WorkflowChain.Status.RUNNING));

        String yaml = "steps:\n  - agent: dev-bot\n    prompt: build it\n";
        String result = handler.execute(Map.of(
                "toolName", "create_workflow", "name", "yaml-flow", "yaml", yaml));

        ArgumentCaptor<CreateWorkflowRequest> captor =
                ArgumentCaptor.forClass(CreateWorkflowRequest.class);
        verify(workflowService).createAndStart(captor.capture());
        // "prompt" is accepted as an alias of "promptTemplate"
        assertThat(captor.getValue().getSteps().get(0).getPromptTemplate()).isEqualTo("build it");
        assertThat(result).contains("created and started");
    }

    @Test
    void createWorkflow_acceptsYamlTopLevelListOfSteps() {
        when(workflowService.createAndStart(any())).thenReturn(response(UUID.randomUUID(), WorkflowChain.Status.RUNNING));

        String yaml = "- agent: dev-bot\n  promptTemplate: review\n";
        String result = handler.execute(Map.of(
                "toolName", "create_workflow", "name", "list-flow", "yaml", yaml));

        assertThat(result).contains("created and started").contains("steps: 1");
    }

    @Test
    void createWorkflow_resolvesAgentByRoleSkippingRetiredAgents() {
        when(agentRepository.findByName("qa")).thenReturn(Optional.empty());
        UUID activeQa = UUID.randomUUID();
        when(agentRepository.findByRole("qa")).thenReturn(List.of(
                Agent.builder().id(UUID.randomUUID()).healthStatus(HealthStatus.RETIRED).build(),
                Agent.builder().id(activeQa).healthStatus(HealthStatus.HEALTHY).build()));
        when(workflowService.createAndStart(any())).thenReturn(response(UUID.randomUUID(), WorkflowChain.Status.RUNNING));

        handler.execute(Map.of(
                "toolName", "create_workflow", "name", "role-flow",
                "steps", List.of(Map.of("role", "qa", "promptTemplate", "test it"))));

        ArgumentCaptor<CreateWorkflowRequest> captor =
                ArgumentCaptor.forClass(CreateWorkflowRequest.class);
        verify(workflowService).createAndStart(captor.capture());
        assertThat(captor.getValue().getSteps().get(0).getAgentId()).isEqualTo(activeQa);
    }

    @Test
    void createWorkflow_stepWithoutAgentReferenceReturnsIndexedError() {
        String result = handler.execute(Map.of(
                "toolName", "create_workflow", "name", "wf",
                "steps", List.of(Map.of("promptTemplate", "no agent"))));

        assertThat(result).isEqualTo(
                "Error: Step 1 is missing an agent (agentId, agentName, or role)");
        verifyNoInteractions(workflowService);
    }

    @Test
    void createWorkflow_stepWithoutPromptReturnsIndexedError() {
        String result = handler.execute(Map.of(
                "toolName", "create_workflow", "name", "wf",
                "steps", List.of(Map.of("agent", "dev-bot"))));

        assertThat(result).isEqualTo("Error: Step 1 is missing a promptTemplate");
        verifyNoInteractions(workflowService);
    }

    @Test
    void createWorkflow_missingStepsAndYamlReturnsError() {
        String result = handler.execute(Map.of("toolName", "create_workflow", "name", "wf"));

        assertThat(result).startsWith("Error: Missing required parameter: steps");
        verifyNoInteractions(workflowService);
    }

    @Test
    void createWorkflow_defaultsMaxIterationsWhenUnparsable() {
        when(workflowService.createAndStart(any())).thenReturn(response(UUID.randomUUID(), WorkflowChain.Status.RUNNING));

        handler.execute(Map.of(
                "toolName", "create_workflow", "name", "wf",
                "steps", List.of(
                        Map.of("agent", "dev-bot", "promptTemplate", "a", "maxIterations", "5"),
                        Map.of("agent", "dev-bot", "promptTemplate", "b", "maxIterations", "lots"))));

        ArgumentCaptor<CreateWorkflowRequest> captor =
                ArgumentCaptor.forClass(CreateWorkflowRequest.class);
        verify(workflowService).createAndStart(captor.capture());
        assertThat(captor.getValue().getSteps().get(0).getMaxIterations()).isEqualTo(5);
        assertThat(captor.getValue().getSteps().get(1).getMaxIterations()).isEqualTo(3);
    }

    @Test
    void getWorkflow_rendersStepBreakdown() {
        UUID wfId = UUID.randomUUID();
        UUID stepAgent = UUID.randomUUID();
        when(workflowService.getWorkflow(wfId)).thenReturn(WorkflowResponse.builder()
                .id(wfId).name("release").status(WorkflowChain.Status.RUNNING)
                .currentStepIndex(1).totalSteps(2)
                .steps(List.of(
                        WorkflowResponse.StepInfo.builder()
                                .index(0).agentId(stepAgent).status(WorkflowStep.Status.COMPLETED).build(),
                        WorkflowResponse.StepInfo.builder()
                                .index(1).agentId(stepAgent).status(WorkflowStep.Status.RUNNING).build()))
                .build());

        String result = handler.execute(Map.of("toolName", "get_workflow", "id", wfId.toString()));

        assertThat(result).contains("Workflow: release")
                .contains("Status: RUNNING")
                .contains("Step: 2/2")
                .contains("- Step 1").contains("COMPLETED")
                .contains("- Step 2").contains(stepAgent.toString());
    }

    @Test
    void getWorkflow_missingIdReturnsError() {
        String result = handler.execute(Map.of("toolName", "get_workflow"));

        assertThat(result).startsWith("Error").contains("id");
        verifyNoInteractions(workflowService);
    }

    @Test
    void listWorkflows_reportsWhenEmpty() {
        when(workflowService.listWorkflows()).thenReturn(List.of());

        String result = handler.execute(Map.of("toolName", "list_workflows"));

        assertThat(result).isEqualTo("No workflows found.");
    }

    @Test
    void listWorkflows_summarizesEachWorkflow() {
        UUID wfId = UUID.randomUUID();
        when(workflowService.listWorkflows()).thenReturn(List.of(
                WorkflowResponse.builder().id(wfId).name("nightly")
                        .status(WorkflowChain.Status.COMPLETED).currentStepIndex(2).totalSteps(3).build()));

        String result = handler.execute(Map.of("toolName", "list_workflows"));

        assertThat(result).contains("Workflows (1 total)")
                .contains("nightly").contains(wfId.toString())
                .contains("COMPLETED").contains("Step: 3/3");
    }

    @Test
    void cancelWorkflow_delegatesAndReportsStatus() {
        UUID wfId = UUID.randomUUID();
        when(workflowService.cancelWorkflow(wfId)).thenReturn(response(wfId, WorkflowChain.Status.CANCELLED));

        String result = handler.execute(Map.of("toolName", "cancel_workflow", "id", wfId.toString()));

        assertThat(result).contains("cancelled").contains("CANCELLED");
    }

    @Test
    void retryWorkflowStep_requiresNonNegativeStepIndex() {
        String result = handler.execute(Map.of(
                "toolName", "retry_workflow_step", "id", UUID.randomUUID().toString()));

        assertThat(result).startsWith("Error").contains("stepIndex");
        verifyNoInteractions(workflowService);
    }

    @Test
    void retryWorkflowStep_delegatesWithParsedIndex() {
        UUID wfId = UUID.randomUUID();
        when(workflowService.retryStep(wfId, 2)).thenReturn(response(wfId, WorkflowChain.Status.RUNNING));

        String result = handler.execute(Map.of(
                "toolName", "retry_workflow_step", "id", wfId.toString(), "stepIndex", 2));

        verify(workflowService).retryStep(wfId, 2);
        assertThat(result).contains("step 2 retried").contains("RUNNING");
    }

    @Test
    void serviceExceptionIsMappedToErrorString() {
        UUID wfId = UUID.randomUUID();
        when(workflowService.cancelWorkflow(wfId))
                .thenThrow(new IllegalStateException("workflow already finished"));

        String result = handler.execute(Map.of(
                "toolName", "cancel_workflow", "id", wfId.toString()));

        assertThat(result).isEqualTo("Error: workflow already finished");
    }
}
