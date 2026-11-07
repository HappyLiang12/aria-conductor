package io.aria.conductor.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.agent.dto.CreateWorkflowRequest;
import io.aria.conductor.agent.dto.RunResponse;
import io.aria.conductor.agent.dto.WorkflowResponse;
import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.common.exception.InvalidStateTransitionException;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceTest {

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
    void createAndStart_sanitizesNonAsciiName() {
        UUID agentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        CreateWorkflowRequest request = CreateWorkflowRequest.builder()
                .name("QA\u2192Dev")
                .steps(List.of(
                        CreateWorkflowRequest.StepDef.builder()
                                .agentId(agentId)
                                .promptTemplate("Do something")
                                .maxIterations(3)
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

        assertThat(response.getName()).isEqualTo("QA-Dev");
        assertThat(response.getName()).matches("[\\x20-\\x7E]*");

        ArgumentCaptor<WorkflowChain> captor = ArgumentCaptor.forClass(WorkflowChain.class);
        verify(workflowChainRepository, atLeast(1)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getName()).isEqualTo("QA-Dev");
    }

    @Test
    void cancelWorkflow_withActiveRun_cancelsRun() {
        UUID chainId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        WorkflowStep step = WorkflowStep.builder()
                .agentId(UUID.randomUUID())
                .promptTemplate("Test prompt")
                .maxIterations(3)
                .status(WorkflowStep.Status.RUNNING)
                .runId(runId)
                .build();

        WorkflowChain chain = WorkflowChain.builder()
                .id(chainId)
                .name("test-chain")
                .status(WorkflowChain.Status.RUNNING)
                .currentStepIndex(0)
                .stepsJson(workflowService.serializeSteps(List.of(step)))
                .createdAt(Instant.now())
                .build();

        when(workflowChainRepository.findById(chainId)).thenReturn(Optional.of(chain));
        when(workflowChainRepository.save(any())).thenReturn(chain);

        WorkflowResponse response = workflowService.cancelWorkflow(chainId);

        assertThat(response.getStatus()).isEqualTo(WorkflowChain.Status.CANCELLED);
        verify(runService).cancelRun(runId);
    }

    @Test
    void cancelWorkflow_runAlreadyCompleted_succeeds() {
        UUID chainId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        WorkflowStep step = WorkflowStep.builder()
                .agentId(UUID.randomUUID())
                .promptTemplate("Test prompt")
                .maxIterations(3)
                .status(WorkflowStep.Status.RUNNING)
                .runId(runId)
                .build();

        WorkflowChain chain = WorkflowChain.builder()
                .id(chainId)
                .name("test-chain")
                .status(WorkflowChain.Status.RUNNING)
                .currentStepIndex(0)
                .stepsJson(workflowService.serializeSteps(List.of(step)))
                .createdAt(Instant.now())
                .build();

        when(workflowChainRepository.findById(chainId)).thenReturn(Optional.of(chain));
        when(workflowChainRepository.save(any())).thenReturn(chain);
        when(runService.cancelRun(runId)).thenThrow(
                new InvalidStateTransitionException("Run", "COMPLETED", "CANCELLED"));

        WorkflowResponse response = workflowService.cancelWorkflow(chainId);

        assertThat(response.getStatus()).isEqualTo(WorkflowChain.Status.CANCELLED);
        verify(runService).cancelRun(runId);
    }

    @Test
    void cancelWorkflow_withoutRunId_doesNotCallCancelRun() {
        UUID chainId = UUID.randomUUID();

        WorkflowStep step = WorkflowStep.builder()
                .agentId(UUID.randomUUID())
                .promptTemplate("Test prompt")
                .maxIterations(3)
                .status(WorkflowStep.Status.PENDING)
                .build();

        WorkflowChain chain = WorkflowChain.builder()
                .id(chainId)
                .name("test-chain")
                .status(WorkflowChain.Status.PENDING)
                .currentStepIndex(0)
                .stepsJson(workflowService.serializeSteps(List.of(step)))
                .createdAt(Instant.now())
                .build();

        when(workflowChainRepository.findById(chainId)).thenReturn(Optional.of(chain));
        when(workflowChainRepository.save(any())).thenReturn(chain);

        WorkflowResponse response = workflowService.cancelWorkflow(chainId);

        assertThat(response.getStatus()).isEqualTo(WorkflowChain.Status.CANCELLED);
        verify(runService, never()).cancelRun(any());
    }
}
