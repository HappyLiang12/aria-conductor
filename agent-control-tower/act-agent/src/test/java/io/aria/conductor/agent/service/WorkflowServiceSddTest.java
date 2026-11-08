package io.aria.conductor.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.agent.dto.CreateRunRequest;
import io.aria.conductor.agent.dto.RunResponse;
import io.aria.conductor.agent.dto.WorkflowResponse;
import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.common.event.WorkflowAdvancedEvent;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SDD loop-back tests for {@link WorkflowService}:
 * rescheduleStep (DEFECT / SPEC_GAP loop-back), cancelWorkflow(WAITING_APPROVAL),
 * and findStepIndexByKind chain lookup scope.
 * Mock setup mirrors {@link WorkflowServiceExistingTest} (real ObjectMapper,
 * manual constructor wiring).
 */
@ExtendWith(MockitoExtension.class)
class WorkflowServiceSddTest {

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

    /** BA(completed) -> DEV(at index 1) -> QA(pending); DEV carries the given attemptCount. */
    private WorkflowChain chainWithDevStepAt(int index, int attemptCount) {
        List<WorkflowStep> steps = new ArrayList<>();
        steps.add(WorkflowStep.builder()
                .agentId(UUID.randomUUID()).promptTemplate("BA: gather spec")
                .kind(WorkflowStep.StepKind.BA).maxIterations(3)
                .status(WorkflowStep.Status.COMPLETED).build());
        steps.add(WorkflowStep.builder()
                .agentId(UUID.randomUUID()).promptTemplate("DEV: implement feature")
                .kind(WorkflowStep.StepKind.DEV).maxIterations(3)
                .status(WorkflowStep.Status.RUNNING).attemptCount(attemptCount).build());
        steps.add(WorkflowStep.builder()
                .agentId(UUID.randomUUID()).promptTemplate("QA: verify")
                .kind(WorkflowStep.StepKind.QA).maxIterations(3)
                .status(WorkflowStep.Status.PENDING).build());

        return WorkflowChain.builder()
                .id(UUID.randomUUID())
                .name("SDD Test Chain")
                .status(WorkflowChain.Status.RUNNING)
                .currentStepIndex(index)
                .stepsJson(serializeSteps(steps))
                .createdAt(Instant.now())
                .build();
    }

    // ==================== rescheduleStep ====================

    @Test
    void rescheduleStep_incrementsAttempt_appendsFeedback_startsNewRun() {
        // chain with a DEV step at index 1, attemptCount 0
        WorkflowChain chain = chainWithDevStepAt(1, 0);
        when(workflowChainRepository.findById(chain.getId())).thenReturn(Optional.of(chain));
        when(workflowChainRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(runService.createRun(any())).thenReturn(RunResponse.builder().id(UUID.randomUUID()).build());

        workflowService.rescheduleStep(chain.getId(), 1, "QA found a defect: off-by-one");

        List<WorkflowStep> steps = deserializeSteps(chain.getStepsJson());
        assertThat(steps.get(1).getAttemptCount()).isEqualTo(1);
        assertThat(steps.get(1).getPromptTemplate()).contains("QA found a defect: off-by-one");
        // startStep immediately re-runs the step, so it is RUNNING again with a fresh run
        assertThat(steps.get(1).getStatus()).isEqualTo(WorkflowStep.Status.RUNNING);
        verify(runService).createRun(any());
    }

    @Test
    void rescheduleStep_recordsTheSupersededRunId() {
        // R6: startStep overwrites step.runId on every loop-back, so without this history
        // the superseded attempt's REVIEW card is unreachable forever.
        WorkflowChain chain = chainWithDevStepAt(1, 0);
        UUID supersededRunId = UUID.randomUUID();
        List<WorkflowStep> before = deserializeSteps(chain.getStepsJson());
        before.get(1).setRunId(supersededRunId);
        chain.setStepsJson(serializeSteps(before));

        when(workflowChainRepository.findById(chain.getId())).thenReturn(Optional.of(chain));
        when(workflowChainRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        UUID currentRunId = UUID.randomUUID();
        when(runService.createRun(any())).thenReturn(RunResponse.builder().id(currentRunId).build());

        workflowService.rescheduleStep(chain.getId(), 1, "QA found a defect: off-by-one");

        ArgumentCaptor<WorkflowChain> saved = ArgumentCaptor.forClass(WorkflowChain.class);
        verify(workflowChainRepository, atLeastOnce()).save(saved.capture());
        // The last saved state is the one startStep wrote: the fresh run id, and the
        // history that still names the superseded attempt, round-tripped through JSON.
        List<WorkflowStep> roundTripped = deserializeSteps(saved.getValue().getStepsJson());
        WorkflowStep dev = roundTripped.get(1);
        assertThat(dev.getPreviousRunIds()).containsExactly(supersededRunId);
        assertThat(dev.getRunId()).isEqualTo(currentRunId).isNotEqualTo(supersededRunId);
    }

    @Test
    void rescheduleStep_replacesThePreviousRoundsFeedback() {
        // R9: R5's 6,000-char feedback carried over a second round used to exceed
        // startStep's 10,000-char HEAD truncation, cutting off the newest findings.
        WorkflowChain chain = chainWithDevStepAt(1, 0);
        when(workflowChainRepository.findById(chain.getId())).thenReturn(Optional.of(chain));
        when(workflowChainRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(runService.createRun(any()))
                .thenAnswer(inv -> RunResponse.builder().id(UUID.randomUUID()).build());

        String firstRound = "A".repeat(6_000);
        String secondRound = "B".repeat(6_000);

        workflowService.rescheduleStep(chain.getId(), 1, firstRound);
        workflowService.rescheduleStep(chain.getId(), 1, secondRound);

        ArgumentCaptor<CreateRunRequest> prompts = ArgumentCaptor.forClass(CreateRunRequest.class);
        verify(runService, times(2)).createRun(prompts.capture());
        String firstPrompt = prompts.getAllValues().get(0).getPromptSeed();
        String secondPrompt = prompts.getAllValues().get(1).getPromptSeed();

        assertThat(firstPrompt).contains(firstRound);
        assertThat(secondPrompt).contains(secondRound);
        assertThat(secondPrompt).doesNotContain(firstRound);
        assertThat(secondPrompt).doesNotContain("[truncated]");
        assertThat(secondPrompt.length()).isLessThanOrEqualTo(10_000);
    }

    @Test
    void rescheduleStep_exceedingMaxAttempts_failsChain() {
        // DEV step already at attemptCount == maxAttempts (3)
        WorkflowChain chain = chainWithDevStepAt(1, 3);
        when(workflowChainRepository.findById(chain.getId())).thenReturn(Optional.of(chain));
        when(workflowChainRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        workflowService.rescheduleStep(chain.getId(), 1, "still broken");

        assertThat(chain.getStatus()).isEqualTo(WorkflowChain.Status.FAILED);
        assertThat(chain.getCompletedAt()).isNotNull();
        verify(runService, never()).createRun(any());
    }

    @Test
    void rescheduleStep_exhaustionPublishesTheFailedChainEvent() {
        // R8: an exhausted SDD loop must announce the dead chain with the same event
        // shape as markStepFailed, so the completion listener settles the dead chain's
        // cards and listeners learn the chain died.
        WorkflowChain chain = chainWithDevStepAt(1, 3);
        when(workflowChainRepository.findById(chain.getId())).thenReturn(Optional.of(chain));
        when(workflowChainRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        workflowService.rescheduleStep(chain.getId(), 1, "still broken");

        ArgumentCaptor<WorkflowAdvancedEvent> event = ArgumentCaptor.forClass(WorkflowAdvancedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().getWorkflowId()).isEqualTo(chain.getId());
        assertThat(event.getValue().getChainStatus()).isEqualTo(WorkflowChain.Status.FAILED);
        assertThat(event.getValue().getCompletedStep()).isEqualTo(1);
        assertThat(event.getValue().getNextStep()).isEqualTo(-1);
    }

    @Test
    void rescheduleStep_indexOutOfRange_throws() {
        WorkflowChain chain = chainWithDevStepAt(1, 0);
        when(workflowChainRepository.findById(chain.getId())).thenReturn(Optional.of(chain));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> workflowService.rescheduleStep(chain.getId(), 99, "nope"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(runService, never()).createRun(any());
    }

    @Test
    void rescheduleStep_terminalChainState_throws() {
        WorkflowChain chain = chainWithDevStepAt(1, 0);
        chain.setStatus(WorkflowChain.Status.COMPLETED);
        when(workflowChainRepository.findById(chain.getId())).thenReturn(Optional.of(chain));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> workflowService.rescheduleStep(chain.getId(), 1, "late feedback"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(runService, never()).createRun(any());
    }

    // ==================== stepRunIds ====================

    @Test
    void stepRunIds_returnsSupersededThenCurrentIdsInStepOrder() {
        UUID baRunId = UUID.randomUUID();
        UUID supersededDevRunId = UUID.randomUUID();
        UUID currentDevRunId = UUID.randomUUID();
        List<WorkflowStep> steps = new ArrayList<>();
        steps.add(WorkflowStep.builder()
                .agentId(UUID.randomUUID()).promptTemplate("BA: gather spec")
                .kind(WorkflowStep.StepKind.BA).status(WorkflowStep.Status.COMPLETED)
                .runId(baRunId).build());
        steps.add(WorkflowStep.builder()
                .agentId(UUID.randomUUID()).promptTemplate("DEV: implement feature")
                .kind(WorkflowStep.StepKind.DEV).status(WorkflowStep.Status.RUNNING)
                .runId(currentDevRunId).previousRunIds(List.of(supersededDevRunId)).build());
        steps.add(WorkflowStep.builder()
                .agentId(UUID.randomUUID()).promptTemplate("QA: verify")
                .kind(WorkflowStep.StepKind.QA).status(WorkflowStep.Status.PENDING).build());
        WorkflowChain chain = WorkflowChain.builder()
                .id(UUID.randomUUID()).name("SDD run ids")
                .status(WorkflowChain.Status.RUNNING)
                .currentStepIndex(1)
                .stepsJson(serializeSteps(steps))
                .createdAt(Instant.now())
                .build();
        when(workflowChainRepository.findById(chain.getId())).thenReturn(Optional.of(chain));

        assertThat(workflowService.stepRunIds(chain.getId()))
                .containsExactly(baRunId, supersededDevRunId, currentDevRunId);
    }

    @Test
    void stepRunIds_unknownChain_throws() {
        UUID unknown = UUID.randomUUID();
        when(workflowChainRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> workflowService.stepRunIds(unknown))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ==================== cancelWorkflow (WAITING_APPROVAL) ====================

    @Test
    void cancelWorkflow_acceptsWaitingApproval() {
        WorkflowChain chain = chainWithDevStepAt(1, 1);
        chain.setStatus(WorkflowChain.Status.WAITING_APPROVAL);
        when(workflowChainRepository.findById(chain.getId())).thenReturn(Optional.of(chain));
        when(workflowChainRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WorkflowResponse resp = workflowService.cancelWorkflow(chain.getId());

        assertThat(chain.getStatus()).isEqualTo(WorkflowChain.Status.CANCELLED);
        assertThat(resp.getStatus()).isEqualTo(WorkflowChain.Status.CANCELLED);
        assertThat(chain.getCompletedAt()).isNotNull();
    }

    @Test
    void cancelWorkflow_stillRejectsCompleted() {
        WorkflowChain chain = chainWithDevStepAt(1, 1);
        chain.setStatus(WorkflowChain.Status.COMPLETED);
        when(workflowChainRepository.findById(chain.getId())).thenReturn(Optional.of(chain));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> workflowService.cancelWorkflow(chain.getId()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== findStepIndexByKind ====================

    @Test
    void findStepIndexByKind_returnsFirstMatchingIndex() {
        WorkflowChain chain = chainWithDevStepAt(1, 0);

        assertThat(workflowService.findStepIndexByKind(chain, WorkflowStep.StepKind.BA)).isEqualTo(0);
        assertThat(workflowService.findStepIndexByKind(chain, WorkflowStep.StepKind.DEV)).isEqualTo(1);
        assertThat(workflowService.findStepIndexByKind(chain, WorkflowStep.StepKind.QA)).isEqualTo(2);
        assertThat(workflowService.findStepIndexByKind(chain, WorkflowStep.StepKind.CODE_REVIEW)).isEqualTo(-1);
    }

    @Test
    void findStepIndexByKind_treatsNullKindAsGeneric() {
        // Pre-existing chains round-tripped through steps_json may carry kind=null
        List<WorkflowStep> steps = new ArrayList<>();
        WorkflowStep generic = WorkflowStep.builder()
                .agentId(UUID.randomUUID()).promptTemplate("legacy step")
                .maxIterations(3).status(WorkflowStep.Status.PENDING).build();
        generic.setKind(null); // simulate a legacy step with no kind
        steps.add(generic);
        WorkflowChain chain = WorkflowChain.builder()
                .id(UUID.randomUUID()).name("Legacy")
                .status(WorkflowChain.Status.RUNNING)
                .currentStepIndex(0)
                .stepsJson(serializeSteps(steps))
                .createdAt(Instant.now())
                .build();

        assertThat(workflowService.findStepIndexByKind(chain, WorkflowStep.StepKind.GENERIC)).isEqualTo(0);
    }
}
