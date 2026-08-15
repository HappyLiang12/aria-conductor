package io.aria.conductor.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.agent.dto.CreateWorkflowRequest;
import io.aria.conductor.agent.dto.RunResponse;
import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.common.model.WorkflowStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * R-F4: the SDD-kind governance guard must live in {@link WorkflowService#createAndStart}
 * so the REST path (and any other caller) cannot bypass the SPEC_REVIEW gate by creating
 * BA/DEV/QA steps directly. Only the governed {@code instantiate_template} path may create
 * SDD chains.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowServiceSddGuardTest {

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
    void createAndStart_rejectsSddKinds() {
        CreateWorkflowRequest request = CreateWorkflowRequest.builder()
                .name("sdd-bypass")
                .steps(List.of(stepDef(UUID.randomUUID(), "Write spec", WorkflowStep.StepKind.BA)))
                .build();

        assertThatThrownBy(() -> workflowService.createAndStart(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("instantiate_template");

        verifyNoInteractions(workflowChainRepository, runService);
    }

    @Test
    void createAndStart_genericKindsStillAllowed() {
        UUID agentId = UUID.randomUUID();
        CreateWorkflowRequest request = CreateWorkflowRequest.builder()
                .name("generic-chain")
                .steps(List.of(
                        stepDef(agentId, "Do generic work", WorkflowStep.StepKind.GENERIC),
                        stepDef(UUID.randomUUID(), "Review code", WorkflowStep.StepKind.CODE_REVIEW)
                ))
                .build();

        when(workflowChainRepository.save(any())).thenAnswer(inv -> {
            WorkflowChain chain = inv.getArgument(0);
            if (chain.getId() == null) chain.setId(UUID.randomUUID());
            if (chain.getCreatedAt() == null) chain.setCreatedAt(Instant.now());
            return chain;
        });
        when(runService.createRun(any())).thenReturn(RunResponse.builder()
                .id(UUID.randomUUID()).agentId(agentId).build());

        workflowService.createAndStart(request);

        verify(runService).createRun(any());
    }

    @Test
    void createAndStart_allowsSddKinds_whenTemplateFlagSet() {
        UUID agentId = UUID.randomUUID();
        CreateWorkflowRequest request = CreateWorkflowRequest.builder()
                .name("sdd-from-template")
                .allowSddSteps(true)
                .steps(List.of(stepDef(agentId, "Write spec", WorkflowStep.StepKind.BA)))
                .build();

        when(workflowChainRepository.save(any())).thenAnswer(inv -> {
            WorkflowChain chain = inv.getArgument(0);
            if (chain.getId() == null) chain.setId(UUID.randomUUID());
            if (chain.getCreatedAt() == null) chain.setCreatedAt(Instant.now());
            return chain;
        });
        when(runService.createRun(any())).thenReturn(RunResponse.builder()
                .id(UUID.randomUUID()).agentId(agentId).build());

        workflowService.createAndStart(request);

        verify(runService).createRun(any());
    }

    private static CreateWorkflowRequest.StepDef stepDef(UUID agentId, String prompt,
                                                          WorkflowStep.StepKind kind) {
        return CreateWorkflowRequest.StepDef.builder()
                .agentId(agentId)
                .promptTemplate(prompt)
                .maxIterations(3)
                .kind(kind)
                .build();
    }
}
