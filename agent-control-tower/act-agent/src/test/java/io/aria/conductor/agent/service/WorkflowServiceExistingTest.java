package io.aria.conductor.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.agent.dto.CreateWorkflowRequest;
import io.aria.conductor.agent.dto.RunResponse;
import io.aria.conductor.agent.dto.WorkflowResponse;
import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.common.model.WorkflowStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceExistingTest {

    @Mock
    WorkflowChainRepository workflowChainRepository;

    @Mock
    RunService runService;

    @Mock
    ApplicationEventPublisher eventPublisher;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WorkflowService workflowService;

    @BeforeEach
    void setUp() {
        workflowService = new WorkflowService(
                workflowChainRepository, runService, objectMapper, eventPublisher);
    }

    // ---- helpers ----

    private String serializeSteps(List<WorkflowStep> steps) {
        try {
            return objectMapper.writeValueAsString(steps);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private List<WorkflowStep> deserializeSteps(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private WorkflowStep pendingStep(UUID agentId, String prompt) {
        return WorkflowStep.builder()
                .agentId(agentId)
                .promptTemplate(prompt)
                .maxIterations(3)
                .status(WorkflowStep.Status.PENDING)
                .build();
    }

    // ==================== createAndStart ====================

    @Test
    void createAndStart_shouldCreateChainAndStartFirstStep() {
        UUID agentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        CreateWorkflowRequest request = CreateWorkflowRequest.builder()
                .name("Test Workflow")
                .steps(List.of(
                        CreateWorkflowRequest.StepDef.builder()
                                .agentId(agentId)
                                .promptTemplate("Do something")
                                .maxIterations(5)
                                .build()
                ))
                .build();

        when(workflowChainRepository.save(any())).thenAnswer(inv -> {
            WorkflowChain chain = inv.getArgument(0);
            if (chain.getId() == null) chain.setId(UUID.randomUUID());
            if (chain.getCreatedAt() == null) chain.setCreatedAt(Instant.now());
            return chain;
        });
        when(runService.createRun(any())).thenReturn(RunResponse.builder()
                .id(runId).agentId(agentId).build());

        WorkflowResponse response = workflowService.createAndStart(request);

        assertThat(response).isNotNull();
        assertThat(response.getName()).isEqualTo("Test Workflow");
        assertThat(response.getTotalSteps()).isEqualTo(1);

        // Verify chain was saved at least twice (initial save + startStep save)
        verify(workflowChainRepository, atLeast(2)).save(any());
        // Verify a run was created
        verify(runService).createRun(any());
    }

    // ==================== advanceWorkflow ====================

    @Test
    void advanceWorkflow_shouldMoveToNextStep() {
        UUID agentId1 = UUID.randomUUID();
        UUID agentId2 = UUID.randomUUID();
        UUID chainId = UUID.randomUUID();
        UUID runId2 = UUID.randomUUID();

        WorkflowStep step0 = WorkflowStep.builder()
                .agentId(agentId1).promptTemplate("Step 0")
                .maxIterations(3).status(WorkflowStep.Status.RUNNING)
                .runId(UUID.randomUUID()).build();
        WorkflowStep step1 = WorkflowStep.builder()
                .agentId(agentId2).promptTemplate("Step 1: {previousOutput}")
                .maxIterations(3).status(WorkflowStep.Status.PENDING).build();

        WorkflowChain chain = WorkflowChain.builder()
                .id(chainId).name("Advance Test")
                .status(WorkflowChain.Status.RUNNING)
                .currentStepIndex(0)
                .stepsJson(serializeSteps(new ArrayList<>(List.of(step0, step1))))
                .createdAt(Instant.now())
                .build();

        when(workflowChainRepository.findById(chainId)).thenReturn(Optional.of(chain));
        when(workflowChainRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(runService.createRun(any())).thenReturn(RunResponse.builder()
                .id(runId2).agentId(agentId2).build());

        boolean advanced = workflowService.advanceWorkflow(chainId, 0, "output from step 0");

        assertThat(advanced).isTrue();

        ArgumentCaptor<WorkflowChain> captor = ArgumentCaptor.forClass(WorkflowChain.class);
        verify(workflowChainRepository, atLeast(1)).save(captor.capture());
        WorkflowChain saved = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(saved.getCurrentStepIndex()).isEqualTo(1);

        List<WorkflowStep> savedSteps = deserializeSteps(saved.getStepsJson());
        assertThat(savedSteps.get(0).getStatus()).isEqualTo(WorkflowStep.Status.COMPLETED);
        assertThat(savedSteps.get(0).getOutput()).isEqualTo("output from step 0");
    }

    // ==================== markStepFailed ====================

    @Test
    void markStepFailed_shouldMarkChainAsFailed() {
        UUID chainId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();

        WorkflowStep step = WorkflowStep.builder()
                .agentId(agentId).promptTemplate("Fail step")
                .maxIterations(3).status(WorkflowStep.Status.RUNNING).build();

        WorkflowChain chain = WorkflowChain.builder()
                .id(chainId).name("Fail Test")
                .status(WorkflowChain.Status.RUNNING)
                .currentStepIndex(0)
                .stepsJson(serializeSteps(new ArrayList<>(List.of(step))))
                .createdAt(Instant.now())
                .build();

        when(workflowChainRepository.findById(chainId)).thenReturn(Optional.of(chain));
        when(workflowChainRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        workflowService.markStepFailed(chainId, 0, "LLM timeout");

        ArgumentCaptor<WorkflowChain> captor = ArgumentCaptor.forClass(WorkflowChain.class);
        verify(workflowChainRepository).save(captor.capture());
        WorkflowChain saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(WorkflowChain.Status.FAILED);

        List<WorkflowStep> savedSteps = deserializeSteps(saved.getStepsJson());
        assertThat(savedSteps.get(0).getStatus()).isEqualTo(WorkflowStep.Status.FAILED);
        assertThat(savedSteps.get(0).getOutput()).contains("LLM timeout");
    }

    // ==================== getWorkflow ====================

    @Test
    void getWorkflow_shouldReturnResponse() {
        UUID chainId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();

        WorkflowStep step = pendingStep(agentId, "Step 0");
        WorkflowChain chain = WorkflowChain.builder()
                .id(chainId).name("Get Test")
                .status(WorkflowChain.Status.PENDING)
                .currentStepIndex(0)
                .stepsJson(serializeSteps(List.of(step)))
                .createdAt(Instant.now())
                .build();

        when(workflowChainRepository.findById(chainId)).thenReturn(Optional.of(chain));

        WorkflowResponse response = workflowService.getWorkflow(chainId);

        assertThat(response.getId()).isEqualTo(chainId);
        assertThat(response.getName()).isEqualTo("Get Test");
        assertThat(response.getStatus()).isEqualTo(WorkflowChain.Status.PENDING);
        assertThat(response.getTotalSteps()).isEqualTo(1);
    }

    @Test
    void getWorkflow_notFound_shouldThrow() {
        UUID chainId = UUID.randomUUID();
        when(workflowChainRepository.findById(chainId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> workflowService.getWorkflow(chainId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ==================== listWorkflows ====================

    @Test
    void listWorkflows_shouldReturnAll() {
        UUID agentId = UUID.randomUUID();
        WorkflowChain c1 = WorkflowChain.builder()
                .id(UUID.randomUUID()).name("WF1")
                .status(WorkflowChain.Status.COMPLETED)
                .currentStepIndex(0)
                .stepsJson(serializeSteps(List.of(pendingStep(agentId, "S1"))))
                .createdAt(Instant.now())
                .build();
        WorkflowChain c2 = WorkflowChain.builder()
                .id(UUID.randomUUID()).name("WF2")
                .status(WorkflowChain.Status.RUNNING)
                .currentStepIndex(0)
                .stepsJson(serializeSteps(List.of(pendingStep(agentId, "S2"))))
                .createdAt(Instant.now())
                .build();

        when(workflowChainRepository.findAll()).thenReturn(List.of(c1, c2));

        List<WorkflowResponse> result = workflowService.listWorkflows();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getName()).isEqualTo("WF1");
        assertThat(result.get(1).getName()).isEqualTo("WF2");
    }
}
