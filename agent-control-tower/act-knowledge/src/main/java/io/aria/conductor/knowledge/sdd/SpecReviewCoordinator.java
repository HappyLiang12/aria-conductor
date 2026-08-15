package io.aria.conductor.knowledge.sdd;

import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.event.ApprovalDecidedEvent;
import io.aria.conductor.common.event.ApprovalRequestedEvent;
import io.aria.conductor.common.event.BaStepCompletedEvent;
import io.aria.conductor.common.event.WorkflowCancelledEvent;
import io.aria.conductor.common.exception.InvalidStateTransitionException;
import io.aria.conductor.common.model.*;
import io.aria.conductor.execution.git.GitBranchService;
import io.aria.conductor.execution.git.GitHandoffMetadata;
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
import java.util.Optional;
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

    /** Upper bound on stored spec content (DB bloat guard): 50 KB. */
    private static final int MAX_SPEC_CONTENT_LENGTH = 50 * 1024;
    private static final String SPEC_ID_MARKER = "SPEC_ID=";
    /** Matches a bare JSON tool-call object on a single trailing line (e.g. {@code {"name":...,"arguments":...}}). */
    private static final java.util.regex.Pattern TRAILING_TOOL_CALL_JSON =
            java.util.regex.Pattern.compile("^\\s*\\{.*\"name\"\\s*:.*\"arguments\".*}\\s*$");

    private final KnowledgeService knowledgeService;
    private final KnowledgeItemRepository itemRepository;
    private final KnowledgeVersionRepository versionRepository;
    private final ApprovalRepository approvalRepository;
    private final WorkflowChainRepository chainRepository;
    private final WorkflowService workflowService;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final GitBranchService gitBranchService;
    private final Duration approvalTimeout;

    public SpecReviewCoordinator(KnowledgeService knowledgeService,
                                 KnowledgeItemRepository itemRepository,
                                 KnowledgeVersionRepository versionRepository,
                                 ApprovalRepository approvalRepository,
                                 WorkflowChainRepository chainRepository,
                                 WorkflowService workflowService,
                                 ApplicationEventPublisher eventPublisher,
                                 PlatformTransactionManager transactionManager,
                                 GitBranchService gitBranchService,
                                 @Value("${approvals.timeout-ms:1800000}") long approvalTimeoutMs) {
        this.knowledgeService = knowledgeService;
        this.itemRepository = itemRepository;
        this.versionRepository = versionRepository;
        this.approvalRepository = approvalRepository;
        this.chainRepository = chainRepository;
        this.workflowService = workflowService;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.gitBranchService = gitBranchService;
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
        String specContent = cleanSpecContent(event.getFinalOutput());
        UUID specItemId = upsertSpecKnowledge(specName, specContent);

        Approval approval = Approval.builder()
                .runId(baRunId)
                .approvalType(Approval.ApprovalType.SPEC_REVIEW)
                .content(specContent)
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
            createBranchAndCommitSpec(chain, approval);
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

    /**
     * Create the chain-scoped branch, commit the cleaned spec to it, then record the
     * resulting HEAD sha so the Dev-completion handler can detect a missing agent push.
     * No-op when the chain carries no repoUrl (non-GitHub SDD flows).
     *
     * @throws io.aria.conductor.execution.git.GitBranchException on any GitHub API failure
     *         (the approval handler propagates it so the transition fails loudly).
     */
    private void createBranchAndCommitSpec(WorkflowChain chain, Approval approval) {
        String repoUrl = GitHandoffMetadata.parse(chain.getTemplateParams())
                .get(GitHandoffMetadata.KEY_REPO_URL);
        if (repoUrl == null || repoUrl.isBlank()) {
            log.info("SDD chain {} has no repoUrl; skipping Git branch handoff", chain.getId());
            return;
        }
        String branchName = GitHandoffMetadata.branchName(chain.getId());
        String specContent = cleanSpecContent(approval.getContent());
        gitBranchService.createBranch(repoUrl, branchName);
        gitBranchService.putFile(repoUrl, branchName, "spec/spec.md", specContent, "sdd: approve spec");
        Optional<String> headSha = gitBranchService.branchHeadSha(repoUrl, branchName);
        headSha.ifPresent(sha -> {
            chain.setTemplateParams(GitHandoffMetadata.withEntry(
                    chain.getTemplateParams(), GitHandoffMetadata.KEY_SPEC_COMMIT_SHA, sha));
            log.info("SDD spec committed to branch {} of {}; recorded spec-commit sha",
                    branchName, repoUrl);
        });
    }

    private String specName(UUID chainId) { return "spec-" + chainId; }

    /**
     * Conservative cleanup of the BA step's raw output before storage:
     * <ol>
     *   <li>If the content contains a {@code # Spec} or {@code ## } heading, extract from the
     *       first heading onward (strip any stream-of-consciousness preamble).</li>
     *   <li>Strip a trailing {@code SPEC_ID=<uuid>} marker.</li>
     *   <li>Truncate to {@value #MAX_SPEC_CONTENT_LENGTH} characters.</li>
     * </ol>
     * If no heading marker exists, the content is stored verbatim (never guess-truncated
     * beyond the size guard).
     */
    static String cleanSpecContent(String content) {
        if (content == null) return null;
        String cleaned = stripToolCallChatter(content);

        int headingIdx = firstHeadingIndex(cleaned);
        if (headingIdx >= 0) {
            cleaned = cleaned.substring(headingIdx);
        }

        int markerIdx = cleaned.lastIndexOf(SPEC_ID_MARKER);
        if (markerIdx >= 0) {
            String tail = cleaned.substring(markerIdx + SPEC_ID_MARKER.length());
            if (tail.matches("[0-9a-fA-F-]{36}\\s*")) {
                cleaned = cleaned.substring(0, markerIdx).stripTrailing();
            }
        }

        if (cleaned.length() > MAX_SPEC_CONTENT_LENGTH) {
            cleaned = cleaned.substring(0, MAX_SPEC_CONTENT_LENGTH);
        }
        return cleaned;
    }

    /**
     * R-F12: strip DSML/tool-call chatter that leaks into the BA output. Conservative by
     * design — only removes obvious tool-call structures, never prose.
     * <ol>
     *   <li>{@code <tool_call...>...</tool_call>} and {@code <invoke...>...</invoke>} XML blocks</li>
     *   <li>{@code ```json ... ```} fenced blocks</li>
     *   <li>trailing lines that look like bare JSON tool-call objects</li>
     * </ol>
     */
    private static String stripToolCallChatter(String content) {
        String cleaned = content;
        cleaned = cleaned.replaceAll("(?is)<tool_call\\b[^>]*>.*?</tool_call>", "");
        cleaned = cleaned.replaceAll("(?is)<invoke\\b[^>]*>.*?</invoke>", "");
        cleaned = cleaned.replaceAll("(?s)```json.*?```", "");
        return stripTrailingToolCallJson(cleaned);
    }

    /** Repeatedly removes a trailing line that looks like a bare JSON tool-call object. */
    private static String stripTrailingToolCallJson(String content) {
        String result = content;
        while (true) {
            String trimmed = result.stripTrailing();
            int lastNewline = trimmed.lastIndexOf('\n');
            String lastLine = (lastNewline >= 0) ? trimmed.substring(lastNewline + 1) : trimmed;
            if (!TRAILING_TOOL_CALL_JSON.matcher(lastLine).matches()) {
                break;
            }
            result = (lastNewline >= 0) ? trimmed.substring(0, lastNewline) : "";
        }
        return result;
    }

    private static int firstHeadingIndex(String content) {
        int specIdx = content.indexOf("# Spec");
        int h2Idx = content.indexOf("## ");
        if (specIdx >= 0 && h2Idx >= 0) return Math.min(specIdx, h2Idx);
        if (specIdx >= 0) return specIdx;
        return h2Idx;
    }
}