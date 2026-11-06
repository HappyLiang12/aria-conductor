package io.aria.conductor.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.agent.dto.CreateWorkflowRequest;
import io.aria.conductor.agent.dto.RunResponse;
import io.aria.conductor.agent.dto.WorkflowResponse;
import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.common.event.WorkflowCancelledEvent;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.common.model.WorkflowStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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
class WorkflowServiceLifecycleTest {

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

    private WorkflowStep runningStep(UUID agentId, String prompt, UUID runId) {
        return WorkflowStep.builder()
                .agentId(agentId)
                .promptTemplate(prompt)
                .maxIterations(3)
                .status(WorkflowStep.Status.RUNNING)
                .runId(runId)
                .build();
    }

    private WorkflowStep failedStep(UUID agentId, String prompt) {
        return WorkflowStep.builder()
                .agentId(agentId)
                .promptTemplate(prompt)
                .maxIterations(3)
                .status(WorkflowStep.Status.FAILED)
                .output("FAILED: something went wrong")
                .build();
    }

    private WorkflowStep completedStep(UUID agentId, String prompt) {
        return WorkflowStep.builder()
                .agentId(agentId)
                .promptTemplate(prompt)
                .maxIterations(3)
                .status(WorkflowStep.Status.COMPLETED)
                .output("done")
                .build();
    }

    private WorkflowChain buildChain(UUID id, String name, WorkflowChain.Status status,
                                      int currentStepIndex, List<WorkflowStep> steps) {
        return WorkflowChain.builder()
                .id(id)
                .name(name)
                .status(status)
                .currentStepIndex(currentStepIndex)
                .stepsJson(serializeSteps(steps))
                .createdAt(Instant.now())
                .build();
    }

    // ==================== cancelWorkflow ====================

    @Nested
    class CancelWorkflow {

        @Test
        void cancelRunningWorkflow_shouldSetCancelledStatus() {
            UUID agentId = UUID.randomUUID();
            UUID runId = UUID.randomUUID();
            UUID chainId = UUID.randomUUID();
            List<WorkflowStep> steps = List.of(runningStep(agentId, "Do task", runId));
            WorkflowChain chain = buildChain(chainId, "Test WF", WorkflowChain.Status.RUNNING, 0, steps);

            when(workflowChainRepository.findById(chainId)).thenReturn(Optional.of(chain));
            when(workflowChainRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            WorkflowResponse response = workflowService.cancelWorkflow(chainId);

            assertThat(response.getStatus()).isEqualTo(WorkflowChain.Status.CANCELLED);

            ArgumentCaptor<WorkflowChain> captor = ArgumentCaptor.forClass(WorkflowChain.class);
            verify(workflowChainRepository).save(captor.capture());
            WorkflowChain saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(WorkflowChain.Status.CANCELLED);
            assertThat(saved.getCompletedAt()).isNotNull();

            List<WorkflowStep> savedSteps = deserializeSteps(saved.getStepsJson());
            assertThat(savedSteps.get(0).getStatus()).isEqualTo(WorkflowStep.Status.SKIPPED);

            verify(eventPublisher).publishEvent(any(WorkflowCancelledEvent.class));
        }

        @Test
        void cancelPendingWorkflow_shouldSkipCurrentStep() {
            UUID agentId = UUID.randomUUID();
            UUID chainId = UUID.randomUUID();
            List<WorkflowStep> steps = List.of(pendingStep(agentId, "Step 1"));
            WorkflowChain chain = buildChain(chainId, "Pending WF", WorkflowChain.Status.PENDING, 0, steps);

            when(workflowChainRepository.findById(chainId)).thenReturn(Optional.of(chain));
            when(workflowChainRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            WorkflowResponse response = workflowService.cancelWorkflow(chainId);

            assertThat(response.getStatus()).isEqualTo(WorkflowChain.Status.CANCELLED);
        }

        @Test
        void cancelCompletedWorkflow_shouldThrowIllegalArgument() {
            UUID chainId = UUID.randomUUID();
            List<WorkflowStep> steps = List.of(completedStep(UUID.randomUUID(), "Done"));
            WorkflowChain chain = buildChain(chainId, "Done WF", WorkflowChain.Status.COMPLETED, 0, steps);

            when(workflowChainRepository.findById(chainId)).thenReturn(Optional.of(chain));

            assertThatThrownBy(() -> workflowService.cancelWorkflow(chainId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("COMPLETED");
        }

        @Test
        void cancelNonExistentWorkflow_shouldThrowNotFound() {
            UUID chainId = UUID.randomUUID();
            when(workflowChainRepository.findById(chainId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> workflowService.cancelWorkflow(chainId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ==================== retryStep ====================

    @Nested
    class RetryStep {

        @Test
        void retryFailedStep_shouldResetStepAndRestart() {
            UUID agentId = UUID.randomUUID();
            UUID chainId = UUID.randomUUID();
            UUID newRunId = UUID.randomUUID();

            WorkflowStep step0 = completedStep(agentId, "Step 0");
            WorkflowStep step1 = failedStep(agentId, "Step 1");
            List<WorkflowStep> steps = new ArrayList<>(List.of(step0, step1));
            WorkflowChain chain = buildChain(chainId, "Failed WF", WorkflowChain.Status.FAILED, 1, steps);

            when(workflowChainRepository.findById(chainId)).thenReturn(Optional.of(chain));
            when(workflowChainRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(runService.createRun(any())).thenReturn(RunResponse.builder()
                    .id(newRunId).agentId(agentId).build());

            WorkflowResponse response = workflowService.retryStep(chainId, 1);

            assertThat(response.getStatus()).isEqualTo(WorkflowChain.Status.RUNNING);

            // Verify save was called at least twice (once for retry reset, once inside startStep)
            ArgumentCaptor<WorkflowChain> captor = ArgumentCaptor.forClass(WorkflowChain.class);
            verify(workflowChainRepository, atLeast(1)).save(captor.capture());
            WorkflowChain lastSave = captor.getAllValues().get(captor.getAllValues().size() - 1);
            List<WorkflowStep> savedSteps = deserializeSteps(lastSave.getStepsJson());
            assertThat(savedSteps.get(1).getStatus()).isEqualTo(WorkflowStep.Status.RUNNING);
            assertThat(savedSteps.get(1).getRunId()).isEqualTo(newRunId);
        }

        @Test
        void retryOnRunningChain_shouldThrowIllegalArgument() {
            UUID chainId = UUID.randomUUID();
            List<WorkflowStep> steps = List.of(runningStep(UUID.randomUUID(), "Running", UUID.randomUUID()));
            WorkflowChain chain = buildChain(chainId, "Running WF", WorkflowChain.Status.RUNNING, 0, steps);

            when(workflowChainRepository.findById(chainId)).thenReturn(Optional.of(chain));

            assertThatThrownBy(() -> workflowService.retryStep(chainId, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("RUNNING");
        }

        @Test
        void retryWithInvalidStepIndex_shouldThrow() {
            UUID chainId = UUID.randomUUID();
            List<WorkflowStep> steps = List.of(failedStep(UUID.randomUUID(), "Step 0"));
            WorkflowChain chain = buildChain(chainId, "Failed WF", WorkflowChain.Status.FAILED, 0, steps);

            when(workflowChainRepository.findById(chainId)).thenReturn(Optional.of(chain));

            assertThatThrownBy(() -> workflowService.retryStep(chainId, 5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("out of range");
        }
    }

    // ==================== updateWorkflow ====================

    @Nested
    class UpdateWorkflow {

        @Test
        void updateName_shouldUpdateChainName() {
            UUID chainId = UUID.randomUUID();
            List<WorkflowStep> steps = List.of(pendingStep(UUID.randomUUID(), "Step 0"));
            WorkflowChain chain = buildChain(chainId, "Old Name", WorkflowChain.Status.PENDING, 0, steps);

            when(workflowChainRepository.findById(chainId)).thenReturn(Optional.of(chain));
            when(workflowChainRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            WorkflowResponse response = workflowService.updateWorkflow(chainId, "New Name", null, null);

            assertThat(response.getName()).isEqualTo("New Name");
            verify(workflowChainRepository).save(any());
        }

        @Test
        void updateRunningWorkflow_shouldThrow() {
            UUID chainId = UUID.randomUUID();
            List<WorkflowStep> steps = List.of(runningStep(UUID.randomUUID(), "Step", UUID.randomUUID()));
            WorkflowChain chain = buildChain(chainId, "Running", WorkflowChain.Status.RUNNING, 0, steps);

            when(workflowChainRepository.findById(chainId)).thenReturn(Optional.of(chain));

            assertThatThrownBy(() -> workflowService.updateWorkflow(chainId, "New", null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("RUNNING");
        }

        @Test
        void appendSteps_shouldAddToExistingSteps() {
            UUID chainId = UUID.randomUUID();
            UUID agentId = UUID.randomUUID();
            List<WorkflowStep> steps = new ArrayList<>(List.of(pendingStep(agentId, "Original step")));
            WorkflowChain chain = buildChain(chainId, "Append Test", WorkflowChain.Status.PENDING, 0, steps);

            when(workflowChainRepository.findById(chainId)).thenReturn(Optional.of(chain));
            when(workflowChainRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            List<CreateWorkflowRequest.StepDef> appendDefs = List.of(
                    CreateWorkflowRequest.StepDef.builder()
                            .agentId(agentId).promptTemplate("Appended step").maxIterations(5).build()
            );

            WorkflowResponse response = workflowService.updateWorkflow(chainId, null, null, appendDefs);

            assertThat(response.getTotalSteps()).isEqualTo(2);

            ArgumentCaptor<WorkflowChain> captor = ArgumentCaptor.forClass(WorkflowChain.class);
            verify(workflowChainRepository).save(captor.capture());
            List<WorkflowStep> savedSteps = deserializeSteps(captor.getValue().getStepsJson());
            assertThat(savedSteps).hasSize(2);
            assertThat(savedSteps.get(1).getPromptTemplate()).isEqualTo("Appended step");
        }
    }

    // ==================== deleteWorkflow ====================

    @Nested
    class DeleteWorkflow {

        @Test
        void deleteCompletedWorkflow_shouldDeleteFromRepo() {
            UUID chainId = UUID.randomUUID();
            List<WorkflowStep> steps = List.of(completedStep(UUID.randomUUID(), "Done"));
            WorkflowChain chain = buildChain(chainId, "Done WF", WorkflowChain.Status.COMPLETED, 0, steps);

            when(workflowChainRepository.findById(chainId)).thenReturn(Optional.of(chain));

            workflowService.deleteWorkflow(chainId);

            verify(workflowChainRepository).delete(chain);
        }

        @Test
        void deleteRunningWorkflow_shouldThrow() {
            UUID chainId = UUID.randomUUID();
            List<WorkflowStep> steps = List.of(runningStep(UUID.randomUUID(), "Running", UUID.randomUUID()));
            WorkflowChain chain = buildChain(chainId, "Running WF", WorkflowChain.Status.RUNNING, 0, steps);

            when(workflowChainRepository.findById(chainId)).thenReturn(Optional.of(chain));

            assertThatThrownBy(() -> workflowService.deleteWorkflow(chainId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("RUNNING");
        }
    }

    // ==================== mergeWorkflows ====================

    @Nested
    class MergeWorkflows {

        @Test
        void mergeTwoChains_shouldConcatenateSteps() {
            UUID agentId = UUID.randomUUID();
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            UUID newRunId = UUID.randomUUID();

            WorkflowChain chain1 = buildChain(id1, "WF1", WorkflowChain.Status.COMPLETED, 0,
                    List.of(completedStep(agentId, "Step A")));
            WorkflowChain chain2 = buildChain(id2, "WF2", WorkflowChain.Status.COMPLETED, 0,
                    List.of(completedStep(agentId, "Step B")));

            when(workflowChainRepository.findById(id1)).thenReturn(Optional.of(chain1));
            when(workflowChainRepository.findById(id2)).thenReturn(Optional.of(chain2));
            when(workflowChainRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(runService.createRun(any())).thenReturn(RunResponse.builder()
                    .id(newRunId).agentId(agentId).build());

            WorkflowResponse response = workflowService.mergeWorkflows(List.of(id1, id2), "Merged");

            assertThat(response.getName()).isEqualTo("Merged");
            assertThat(response.getTotalSteps()).isEqualTo(2);
        }

        @Test
        void mergeWithSingleId_shouldThrow() {
            assertThatThrownBy(() -> workflowService.mergeWorkflows(List.of(UUID.randomUUID()), "Solo"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("At least 2");
        }
    }

    // ==================== createFromTemplate ====================

    @Nested
    class CreateFromTemplate {

        @Test
        void createFromTemplate_shouldSubstituteParameters() {
            UUID agentId = UUID.randomUUID();
            UUID templateId = UUID.randomUUID();
            UUID newRunId = UUID.randomUUID();
            UUID newChainId = UUID.randomUUID();

            WorkflowStep templateStep = WorkflowStep.builder()
                    .agentId(agentId)
                    .promptTemplate("Deploy to {environment} using {tool}")
                    .maxIterations(3)
                    .status(WorkflowStep.Status.PENDING)
                    .build();

            WorkflowChain template = WorkflowChain.builder()
                    .id(templateId)
                    .name("Deploy Template")
                    .status(WorkflowChain.Status.COMPLETED)
                    .currentStepIndex(0)
                    .stepsJson(serializeSteps(List.of(templateStep)))
                    .createdAt(Instant.now())
                    .isTemplate(true)
                    .build();

            // New chain created by createAndStart inside createFromTemplate
            WorkflowChain[] savedNewChain = new WorkflowChain[1];

            when(workflowChainRepository.findById(templateId)).thenReturn(Optional.of(template));
            when(workflowChainRepository.save(any())).thenAnswer(inv -> {
                WorkflowChain chain = inv.getArgument(0);
                if (chain.getId() == null) {
                    chain.setId(newChainId);
                    savedNewChain[0] = chain;
                }
                if (chain.getCreatedAt() == null) chain.setCreatedAt(Instant.now());
                return chain;
            });
            // Second findById call for the newly created chain
            when(workflowChainRepository.findById(newChainId)).thenAnswer(inv ->
                    Optional.ofNullable(savedNewChain[0]));
            when(runService.createRun(any())).thenReturn(RunResponse.builder()
                    .id(newRunId).agentId(agentId).build());

            Map<String, String> params = Map.of("environment", "production", "tool", "helm");

            WorkflowResponse response = workflowService.createFromTemplate(templateId, params);

            assertThat(response).isNotNull();
            // Verify the run was created with substituted prompt
            ArgumentCaptor<io.aria.conductor.agent.dto.CreateRunRequest> runCaptor =
                    ArgumentCaptor.forClass(io.aria.conductor.agent.dto.CreateRunRequest.class);
            verify(runService).createRun(runCaptor.capture());
            String prompt = runCaptor.getValue().getPromptSeed();
            assertThat(prompt).contains("production").contains("helm");
            assertThat(prompt).doesNotContain("{environment}").doesNotContain("{tool}");
        }

        @Test
        void createFromNonTemplate_shouldThrow() {
            UUID chainId = UUID.randomUUID();
            WorkflowChain chain = WorkflowChain.builder()
                    .id(chainId)
                    .name("Not a template")
                    .status(WorkflowChain.Status.COMPLETED)
                    .currentStepIndex(0)
                    .stepsJson(serializeSteps(List.of(pendingStep(UUID.randomUUID(), "Step"))))
                    .createdAt(Instant.now())
                    .isTemplate(false)
                    .build();

            when(workflowChainRepository.findById(chainId)).thenReturn(Optional.of(chain));

            assertThatThrownBy(() -> workflowService.createFromTemplate(chainId, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not a template");
        }
    }
}
