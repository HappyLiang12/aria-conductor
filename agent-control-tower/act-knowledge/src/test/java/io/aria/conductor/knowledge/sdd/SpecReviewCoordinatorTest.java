package io.aria.conductor.knowledge.sdd;

import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.event.ApprovalDecidedEvent;
import io.aria.conductor.common.event.ApprovalRequestedEvent;
import io.aria.conductor.common.event.BaStepCompletedEvent;
import io.aria.conductor.common.model.*;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.knowledge.dto.CreateKnowledgeRequest;
import io.aria.conductor.knowledge.dto.KnowledgeItemResponse;
import io.aria.conductor.knowledge.dto.ReviewDecisionRequest;
import io.aria.conductor.knowledge.dto.UpdateKnowledgeRequest;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import io.aria.conductor.knowledge.service.KnowledgeService;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SpecReviewCoordinatorTest {

    @Mock KnowledgeService knowledgeService;
    @Mock KnowledgeItemRepository itemRepository;
    @Mock ApprovalRepository approvalRepository;
    @Mock WorkflowChainRepository chainRepository;
    @Mock WorkflowService workflowService;
    @Mock ApplicationEventPublisher eventPublisher;

    SpecReviewCoordinator coordinator;

    final UUID chainId = UUID.randomUUID();
    final UUID baRunId = UUID.randomUUID();
    final UUID specItemId = UUID.randomUUID();
    final UUID approvalId = UUID.randomUUID();
    final String specContent = "# Spec\n\nThis is the spec content.";

    WorkflowChain chain;
    KnowledgeItemResponse specResponse;
    Approval specReviewApproval;
    Approval toolCallApproval;

    @BeforeEach
    void setUp() {
        specResponse = KnowledgeItemResponse.builder()
                .id(specItemId)
                .name("spec-" + chainId)
                .type(KnowledgeType.SPEC)
                .status(KnowledgeStatus.PENDING)
                .build();

        chain = WorkflowChain.builder()
                .id(chainId)
                .status(WorkflowChain.Status.RUNNING)
                .currentStepIndex(0)
                .stepsJson("[]")
                .build();

        specReviewApproval = Approval.builder()
                .id(approvalId)
                .runId(baRunId)
                .approvalType(Approval.ApprovalType.SPEC_REVIEW)
                .status(ApprovalStatus.PENDING)
                .content(specContent)
                .contentKind(Approval.ContentKind.MARKDOWN)
                .knowledgeItemId(specItemId)
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        toolCallApproval = Approval.builder()
                .id(UUID.randomUUID())
                .runId(baRunId)
                .approvalType(Approval.ApprovalType.TOOL_CALL)
                .status(ApprovalStatus.PENDING)
                .build();

        coordinator = new SpecReviewCoordinator(
                knowledgeService, itemRepository, approvalRepository,
                chainRepository, workflowService, eventPublisher, 1800000L);
    }

    @Test
    void onBaStepCompleted_createsSpecKnowledgeAndApproval_andPausesChain() {
        when(itemRepository.findByName("spec-" + chainId)).thenReturn(Optional.empty());
        when(knowledgeService.submitKnowledge(any())).thenReturn(specResponse);
        when(chainRepository.findById(chainId)).thenReturn(Optional.of(chain));

        coordinator.onBaStepCompleted(new BaStepCompletedEvent(this, chainId, 0, baRunId, specContent));

        verify(knowledgeService).submitKnowledge(argThat(r ->
                r.getType() == KnowledgeType.SPEC && r.getName().equals("spec-" + chainId)));
        ArgumentCaptor<Approval> approvalCaptor = ArgumentCaptor.forClass(Approval.class);
        verify(approvalRepository).save(approvalCaptor.capture());
        Approval saved = approvalCaptor.getValue();
        assertThat(saved.getApprovalType()).isEqualTo(Approval.ApprovalType.SPEC_REVIEW);
        assertThat(saved.getKnowledgeItemId()).isEqualTo(specItemId);
        assertThat(saved.getContent()).contains("# Spec");
        assertThat(saved.getExpiresAt()).isNotNull();
        assertThat(chain.getStatus()).isEqualTo(WorkflowChain.Status.WAITING_APPROVAL);
        verify(eventPublisher).publishEvent(any(ApprovalRequestedEvent.class));
    }

    @Test
    void onBaStepCompleted_isIdempotent_whenApprovalExists() {
        when(approvalRepository.findByRunId(baRunId)).thenReturn(List.of(specReviewApproval));

        coordinator.onBaStepCompleted(new BaStepCompletedEvent(this, chainId, 0, baRunId, "# Spec"));

        verify(knowledgeService, never()).submitKnowledge(any());
        verify(approvalRepository, never()).save(any());
    }

    @Test
    void onApprovalApproved_writesBack_rewritesSpecRef_andAdvances() {
        when(approvalRepository.findById(approvalId)).thenReturn(Optional.of(specReviewApproval));
        when(workflowService.findChainByRunId(baRunId)).thenReturn(chain);
        when(workflowService.findStepIndexByKind(chain, WorkflowStep.StepKind.BA)).thenReturn(0);
        WorkflowStep devStep = WorkflowStep.builder()
                .kind(WorkflowStep.StepKind.DEV)
                .promptTemplate("Implement {specRef}")
                .build();
        when(workflowService.deserializeSteps(anyString())).thenReturn(List.of(devStep));
        doAnswer(inv -> {
            List<WorkflowStep> steps = inv.getArgument(0);
            return "[{\"kind\":\"DEV\",\"promptTemplate\":\"Implement " + specItemId + "\"}]";
        }).when(workflowService).serializeSteps(anyList());

        // Mark approval as APPROVED (as it would be after decision)
        specReviewApproval.setStatus(ApprovalStatus.APPROVED);

        coordinator.onApprovalDecided(new ApprovalDecidedEvent(this, approvalId, ApprovalStatus.APPROVED));

        verify(knowledgeService).reviewKnowledge(eq(specItemId), argThat(r ->
                r.getDecision() == ReviewDecisionRequest.ReviewDecision.APPROVED));
        assertThat(devStep.getPromptTemplate()).doesNotContain("{specRef}");
        assertThat(chain.getStatus()).isEqualTo(WorkflowChain.Status.RUNNING);
        verify(workflowService).advanceWorkflow(eq(chainId), eq(0), any());
    }

    @Test
    void onApprovalDenied_writesBackRejected_andReschedulesBaStep() {
        when(approvalRepository.findById(approvalId)).thenReturn(Optional.of(specReviewApproval));
        when(workflowService.findChainByRunId(baRunId)).thenReturn(chain);
        when(workflowService.findStepIndexByKind(chain, WorkflowStep.StepKind.BA)).thenReturn(0);

        // Mark approval as DENIED
        specReviewApproval.setStatus(ApprovalStatus.DENIED);

        coordinator.onApprovalDecided(new ApprovalDecidedEvent(this, approvalId, ApprovalStatus.DENIED));

        verify(knowledgeService).reviewKnowledge(eq(specItemId), argThat(r ->
                r.getDecision() == ReviewDecisionRequest.ReviewDecision.REJECTED));
        verify(workflowService).rescheduleStep(eq(chainId), eq(0), anyString());
    }

    @Test
    void onApprovalDecided_ignoresToolCallApprovals() {
        when(approvalRepository.findById(any())).thenReturn(Optional.of(toolCallApproval));

        coordinator.onApprovalDecided(new ApprovalDecidedEvent(this, UUID.randomUUID(), ApprovalStatus.APPROVED));

        verifyNoInteractions(knowledgeService, workflowService);
    }
}