package io.aria.conductor.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.common.model.WorkflowStep;
import io.aria.conductor.test.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F18: a run that is a step of any workflow chain is advanced by the auto-chainer,
 * so its card carries no human gate. The membership query must therefore see
 * completed chains too — the QA step's chain is already COMPLETED by the time the
 * step card settles to REVIEW.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowServiceChainMembershipTest {

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

    @Test
    void runOfACompletedChain_isStillARecognisedStep() {
        UUID runId = UUID.randomUUID();
        when(workflowChainRepository.findAll()).thenReturn(List.of(
                chainWith(WorkflowChain.Status.COMPLETED, runId)));

        assertThat(workflowService.isRunPartOfWorkflowChain(runId)).isTrue();
    }

    @Test
    void runOfARunningChain_isRecognised() {
        UUID runId = UUID.randomUUID();
        when(workflowChainRepository.findAll()).thenReturn(List.of(
                chainWith(WorkflowChain.Status.RUNNING, runId)));

        assertThat(workflowService.isRunPartOfWorkflowChain(runId)).isTrue();
    }

    @Test
    void runOfNoChain_isNot() {
        when(workflowChainRepository.findAll()).thenReturn(List.of(
                chainWith(WorkflowChain.Status.RUNNING, UUID.randomUUID())));

        assertThat(workflowService.isRunPartOfWorkflowChain(UUID.randomUUID())).isFalse();
    }

    @Test
    void aSupersededAttempt_isStillARecognisedChainStep() {
        UUID previousRunId = UUID.randomUUID();
        UUID currentRunId = UUID.randomUUID();
        when(workflowChainRepository.findAll()).thenReturn(List.of(
                chainWithPrevious(WorkflowChain.Status.COMPLETED, currentRunId, previousRunId)));

        assertThat(workflowService.isRunPartOfWorkflowChain(previousRunId)).isTrue();
        assertThat(workflowService.isRunPartOfWorkflowChain(currentRunId)).isTrue();
    }

    @Test
    void aNullRunId_isNotAChainStepAndSkipsTheScan() {
        assertThat(workflowService.isRunPartOfWorkflowChain(null)).isFalse();

        verifyNoInteractions(workflowChainRepository);
    }

    private WorkflowChain chainWith(WorkflowChain.Status status, UUID stepRunId) {
        return chainWithPrevious(status, stepRunId);
    }

    private WorkflowChain chainWithPrevious(WorkflowChain.Status status, UUID stepRunId, UUID... previousRunIds) {
        WorkflowStep step = WorkflowStep.builder()
                .agentId(UUID.randomUUID())
                .promptTemplate("step")
                .maxIterations(3)
                .status(WorkflowStep.Status.COMPLETED)
                .runId(stepRunId)
                .previousRunIds(previousRunIds == null ? null : List.of(previousRunIds))
                .build();
        try {
            return TestDataBuilder.aWorkflowChain()
                    .withName("sdd-loop")
                    .withStatus(status)
                    .withStepsJson(objectMapper.writeValueAsString(List.of(step)))
                    .build();
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
