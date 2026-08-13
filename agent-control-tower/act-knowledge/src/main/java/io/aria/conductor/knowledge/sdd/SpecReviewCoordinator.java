package io.aria.conductor.knowledge.sdd;

import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.event.ApprovalDecidedEvent;
import io.aria.conductor.common.event.ApprovalRequestedEvent;
import io.aria.conductor.common.event.BaStepCompletedEvent;
import io.aria.conductor.common.event.WorkflowCancelledEvent;
import io.aria.conductor.common.exception.InvalidStateTransitionException;
import io.aria.conductor.common.model.*;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.knowledge.dto.CreateKnowledgeRequest;
import io.aria.conductor.knowledge.dto.ReviewDecisionRequest;
import io.aria.conductor.knowledge.dto.UpdateKnowledgeRequest;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import io.aria.conductor.knowledge.repository.KnowledgeVersionRepository;
import io.aria.conductor.knowledge.service.KnowledgeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Coordinates the spec-review gate of the SDD loop. On BA-step completion it stores the spec
 * as a versioned SPEC knowledge item, opens a SPEC_REVIEW approval, and pauses the chain.
 * On approval it writes back to knowledge, injects the spec UUID into Dev/QA prompts, and
 * resumes the chain. Idempotent; recovers decided-but-unrouted approvals on startup.
 */
@Slf4j
@Component
public class SpecReviewCoordinator {

    private final KnowledgeService knowledgeService;
    private final KnowledgeItemRepository itemRepository;
    private final KnowledgeVersionRepository versionRepository;
    private final ApprovalRepository approvalRepository;
    private final WorkflowChainRepository chainRepository;
    private final WorkflowService workflowService;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final Duration approvalTimeout;

    public SpecReviewCoordinator(KnowledgeService knowledgeService,
                                 KnowledgeItemRepository itemRepository,
                                 KnowledgeVersionRepository versionRepository,
                                 ApprovalRepository approvalRepository,
                                 WorkflowChainRepository chainRepository,
                                 WorkflowService workflowService,
                                 ApplicationEventPublisher eventPublisher,
                                 PlatformTransactionManager transactionManager,
                                 @Value("${approvals.timeout-ms:1800000}") long approvalTimeoutMs) {
        this.knowledgeService = knowledgeService;
        this.itemRepository = itemRepository;
        this.versionRepository = versionRepository;
        this.approvalRepository = approvalRepository;
        this.chainRepository = chainRepository;
        this.workflowService = workflowService;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.approvalTimeout = Duration.ofMillis(approvalTimeoutMs);
    }

    /** BA step finished: persist the spec, open a SPEC_REVIEW approval, pause the chain. */
    @EventListener
    @Transactional
    public void onBaStepCompleted(BaStepCompletedEvent event) {
        UUID chainId = event.getChainId();
        UUID baRunId = event.getBaRunId();

        // Idempotency: a pending SPEC_REVIEW approval for this BA run means we already handled it.
        boolean alreadyPending = approvalRepository.findByRunId(baRunId).stream()
                .anyMatch(a -> a.getApprovalType() == Approval.ApprovalType.SPEC_REVIEW
                        && a.getStatus() == ApprovalStatus.PENDING);
        if (alreadyPending) {
            log.info("SDD spec approval already pending for BA run {}; skipping", baRunId);
            return;
        }

        String specName = specName(chainId);
        UUID specItemId = upsertSpecKnowledge(specName, event.getFinalOutput());

        Approval approval = Approval.builder()
                .runId(baRunId)
                .approvalType(Approval.ApprovalType.SPEC_REVIEW)
                .content(event.getFinalOutput())
                .contentKind(Approval.ContentKind.MARKDOWN)
                .knowledgeItemId(specItemId)
                .status(ApprovalStatus.PENDING)
                .reason("Spec ready for review: " + specName)
                .expiresAt(Instant.now().plus(approvalTimeout))
                .build();
        approvalRepository.save(approval);

        WorkflowChain chain = chainRepository.findById(chainId).orElse(null);
        if (chain != null) {
            chain.setStatus(WorkflowChain.Status.WAITING_APPROVAL);
            chainRepository.save(chain);
        }

        eventPublisher.publishEvent(new ApprovalRequestedEvent(
                this, approval.getId(), baRunId, null, "SPEC_REVIEW"));
        log.info("SDD spec submitted for review: chain={} spec={} approval={}", chainId, specItemId, approval.getId());
    }

    /** Approval decided: write back to knowledge and route the chain. */
    @EventListener
    @Transactional
    public void onApprovalDecided(ApprovalDecidedEvent event) {
        Approval approval = approvalRepository.findById(event.getApprovalId()).orElse(null);
        if (approval == null || approval.getApprovalType() != Approval.ApprovalType.SPEC_REVIEW) {
            return; // not our concern
        }
        UUID specItemId = approval.getKnowledgeItemId();
        if (specItemId == null) return;

        WorkflowChain chain = workflowService.findChainByRunId(approval.getRunId());
        if (chain == null) {
            log.warn("SDD approval {} resolved but no chain found for run {}; skipping",
                    approval.getId(), approval.getRunId());
            return;
        }
        if (chain.getStatus() != WorkflowChain.Status.WAITING_APPROVAL) {
            log.warn("SDD approval {} resolved but chain {} is {} (not WAITING_APPROVAL); skipping",
                    approval.getId(), chain.getId(), chain.getStatus());
            return; // guards against double-approval re-advancing an already-resumed chain
        }

        boolean approved = approval.getStatus() == ApprovalStatus.APPROVED;
        try {
            knowledgeService.reviewKnowledge(specItemId, ReviewDecisionRequest.builder()
                    .decision(approved ? ReviewDecisionRequest.ReviewDecision.APPROVED
                                       : ReviewDecisionRequest.ReviewDecision.REJECTED)
                    .reason(approval.getReason())
                    .build());
        } catch (InvalidStateTransitionException e) {
            log.warn("Spec knowledge write-back skipped (item {} no longer PENDING): {}",
                    specItemId, e.getMessage());
        }

        int baIdx = workflowService.findStepIndexByKind(chain, WorkflowStep.StepKind.BA);
        if (approved) {
            injectSpecReference(chain, specItemId);
            chain.setStatus(WorkflowChain.Status.RUNNING);
            chainRepository.save(chain);
            workflowService.advanceWorkflow(chain.getId(), baIdx, approval.getContent());
            log.info("SDD spec approved: chain={} advancing to Dev", chain.getId());
        } else {
            // Spec state machine: WAITING_APPROVAL -(REJECTED)-> RUNNING -(re-schedule BA step).
            chain.setStatus(WorkflowChain.Status.RUNNING);
            chainRepository.save(chain);
            workflowService.rescheduleStep(chain.getId(), baIdx,
                    "Spec was rejected: " + (approval.getReason() != null ? approval.getReason() : ""));
            log.info("SDD spec rejected: chain={} re-scheduling BA step", chain.getId());
        }
    }

   /** Startup recovery: re-route APPROVED approvals whose chain is still WAITING_APPROVAL. */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverPendingDecisions() {
        List<Approval> approved = approvalRepository.findByStatusAndApprovalType(
                ApprovalStatus.APPROVED, Approval.ApprovalType.SPEC_REVIEW);
        for (Approval a : approved) {
            WorkflowChain chain = workflowService.findChainByRunId(a.getRunId());
            if (chain != null && chain.getStatus() == WorkflowChain.Status.WAITING_APPROVAL) {
                log.info("SDD startup recovery: re-routing chain {}", chain.getId());
                // Isolate each chain's recovery in its own transaction so one failing
                // run creation does not roll back the recovery of every other chain.
                try {
                    transactionTemplate.executeWithoutResult(s -> onApprovalDecided(
                            new ApprovalDecidedEvent(this, a.getId(), ApprovalStatus.APPROVED)));
                } catch (Exception e) {
                    log.error("SDD startup recovery failed for approval {}: {}", a.getId(), e.getMessage(), e);
                }
            }
        }
    }

    /** A cancelled chain must not leave PENDING SPEC_REVIEW approvals behind (or a resubmit target). */
    @EventListener
    @Transactional
    public void onWorkflowCancelled(WorkflowCancelledEvent event) {
        WorkflowChain chain = chainRepository.findById(event.getWorkflowId()).orElse(null);
        if (chain == null) return;
        int baIdx = workflowService.findStepIndexByKind(chain, WorkflowStep.StepKind.BA);
        if (baIdx < 0) return; // not an SDD chain
        WorkflowStep baStep = workflowService.stepAt(chain, baIdx);
        if (baStep == null || baStep.getRunId() == null) return;
        approvalRepository.findByRunId(baStep.getRunId()).stream()
                .filter(a -> a.getApprovalType() == Approval.ApprovalType.SPEC_REVIEW
                        && a.getStatus() == ApprovalStatus.PENDING)
                .forEach(a -> {
                    a.setStatus(ApprovalStatus.EXPIRED);
                    a.setReason("Workflow cancelled");
                    a.setDecidedAt(Instant.now());
                    approvalRepository.save(a);
                });
    }

    /** Re-create an approval for a chain stuck in WAITING_APPROVAL (e.g. after EXPIRED). */
    @Transactional
    public Approval resubmitApproval(UUID chainId) {
        WorkflowChain chain = chainRepository.findById(chainId)
                .orElseThrow(() -> new IllegalArgumentException("Chain not found: " + chainId));
        if (chain.getStatus() != WorkflowChain.Status.WAITING_APPROVAL) {
            throw new IllegalStateException("Chain " + chainId + " is " + chain.getStatus()
                    + "; resubmit requires WAITING_APPROVAL");
        }
        int baIdx = workflowService.findStepIndexByKind(chain, WorkflowStep.StepKind.BA);
        WorkflowStep baStep = workflowService.stepAt(chain, baIdx);
        String specName = specName(chainId);
        KnowledgeItem item = itemRepository.findByName(specName)
                .orElseThrow(() -> new IllegalStateException("No spec knowledge item: " + specName));
        // Idempotency: a PENDING SPEC_REVIEW approval for this BA run already exists.
        boolean alreadyPending = approvalRepository.findByRunId(baStep.getRunId()).stream()
                .anyMatch(a -> a.getApprovalType() == Approval.ApprovalType.SPEC_REVIEW
                        && a.getStatus() == ApprovalStatus.PENDING);
        if (alreadyPending) {
            throw new IllegalStateException("A SPEC_REVIEW approval is already pending for this chain");
        }
        // Content comes from the latest spec version, not the item description placeholder.
        String content = versionRepository
                .findByKnowledgeItemIdAndVersion(item.getId(), item.getCurrentVersion())
                .map(KnowledgeVersion::getContent)
                .orElse(item.getDescription());
        Approval approval = Approval.builder()
                .runId(baStep.getRunId())
                .approvalType(Approval.ApprovalType.SPEC_REVIEW)
                .content(content)
                .contentKind(Approval.ContentKind.MARKDOWN)
                .knowledgeItemId(item.getId())
                .status(ApprovalStatus.PENDING)
                .reason("Spec resubmitted for review: " + specName)
                .expiresAt(Instant.now().plus(approvalTimeout))
                .build();
        Approval saved = approvalRepository.save(approval);
        eventPublisher.publishEvent(new ApprovalRequestedEvent(
                this, saved.getId(), saved.getRunId(), null, "SPEC_REVIEW"));
        return saved;
    }

    private UUID upsertSpecKnowledge(String name, String content) {
        return itemRepository.findByName(name)
                .map(existing -> knowledgeService.updateKnowledge(existing.getId(),
                        UpdateKnowledgeRequest.builder().content(content).build()).getId())
                .orElseGet(() -> knowledgeService.submitKnowledge(CreateKnowledgeRequest.builder()
                        .name(name)
                        .type(KnowledgeType.SPEC)
                        .description("SDD spec")
                        .content(content)
                        .build()).getId());
    }

    private void injectSpecReference(WorkflowChain chain, UUID specItemId) {
        List<WorkflowStep> steps = workflowService.deserializeSteps(chain.getStepsJson());
        for (WorkflowStep s : steps) {
            if ((s.getKind() == WorkflowStep.StepKind.DEV || s.getKind() == WorkflowStep.StepKind.QA)
                    && s.getPromptTemplate() != null) {
                s.setPromptTemplate(s.getPromptTemplate().replace("{specRef}", specItemId.toString()));
            }
        }
        chain.setStepsJson(workflowService.serializeSteps(steps));
    }

    private String specName(UUID chainId) { return "spec-" + chainId; }
}