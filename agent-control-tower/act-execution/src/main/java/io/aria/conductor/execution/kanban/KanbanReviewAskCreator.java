package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.event.KanbanItemTransitionedEvent;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.execution.repository.ApprovalRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Spec 10.2: every run-completed card entering REVIEW carries a REVIEW_REQUEST
 * ask so the Review column always surfaces a structured decision surface. The ask
 * is keyed on the card's linked run, so a blank or unparseable link gets none.
 * Idempotent: skipped when ANY PENDING ask already exists on the card, regardless
 * of source. A card entering REVIEW with a pending ask (native ACP permission ask
 * included) already carries its decision surface; a second, card-level review ask
 * is noise - and, for a still-running gate-blocked run, its "completed (STATUS)"
 * copy would lie. In-listener failures are swallowed (a display ask must never
 * break the transition); only DB-constraint failures at flush time reach the
 * caller's transaction.
 *
 * <p>F18: a run that is a step of a workflow chain gets no ask at all — the
 * auto-chainer owns that progression, and the gates it produces were left PENDING
 * while the chain advanced and completed.
 */
@Slf4j
@Component
public class KanbanReviewAskCreator {

    private final ApprovalRepository approvalRepository;
    private final KanbanRepository kanbanRepository;
    private final RunRepository runRepository;
    private final WorkflowService workflowService;

    public KanbanReviewAskCreator(ApprovalRepository approvalRepository,
                                  KanbanRepository kanbanRepository,
                                  RunRepository runRepository,
                                  WorkflowService workflowService) {
        this.approvalRepository = approvalRepository;
        this.kanbanRepository = kanbanRepository;
        this.runRepository = runRepository;
        this.workflowService = workflowService;
    }

    @EventListener
    @Transactional
    public void onKanbanItemTransitioned(KanbanItemTransitionedEvent event) {
        if (!"IN_PROGRESS".equals(event.getFromStatus()) || !"REVIEW".equals(event.getToStatus())) {
            return;
        }
        try {
            kanbanRepository.findById(event.getItemId()).ifPresent(item -> {
                boolean hasPendingAsk = !approvalRepository
                        .findByStatusAndKanbanItemId(ApprovalStatus.PENDING, item.getId())
                        .isEmpty();
                if (hasPendingAsk) {
                    return;
                }
                if (item.getLinkedRunId() == null || item.getLinkedRunId().isBlank()) {
                    return; // Approval.runId is NOT NULL - nothing to attach to
                }
                UUID runId;
                try {
                    runId = UUID.fromString(item.getLinkedRunId());
                } catch (IllegalArgumentException e) {
                    // Parsed before the ask is written, not inside the catch that
                    // protects the save: a link this listener cannot read has no run
                    // to attach an ask to, and must not be reported as a save failure.
                    log.warn("Skipping review ask for {} - corrupt linkedRunId: {}",
                            item.getId(), item.getLinkedRunId());
                    return;
                }
                // F18: a chain step is advanced by WorkflowAutoChainer, which owns the
                // gate. Its card needs no REVIEW_REQUEST — the live run left one PENDING
                // ask per step while the chain advanced and completed.
                if (workflowService.isRunPartOfWorkflowChain(runId)) {
                    log.debug("Skipping review ask for {} - run {} is a workflow chain step",
                            item.getId(), runId);
                    return;
                }
                Run run = runRepository.findById(runId).orElse(null);

                String status = run != null && run.getStatus() != null ? run.getStatus().name() : "UNKNOWN";
                String content = "Run " + runId.toString().substring(0, 8) + " completed (" + status
                        + ") - awaiting your review: " + item.getTitle();
                StringBuilder ctx = new StringBuilder()
                        .append("**Task:** ").append(item.getTitle()).append('\n')
                        .append("**Agent:** ").append(item.getAssignee() != null ? item.getAssignee() : "n/a").append('\n')
                        .append("**Run:** ").append(runId).append('\n');
                if (run != null) {
                    // Truthful: never fabricate a completion timestamp; print n/a when absent.
                    ctx.append("**Completed:** ").append(run.getCompletedAt() != null ? run.getCompletedAt().toString() : "n/a").append('\n')
                       .append("**Iterations:** ").append(run.getIterationCount())
                       .append(" | **Tokens:** ").append(run.getTotalTokensUsed()).append('\n');
                    if (item.getDescription() != null && !item.getDescription().isBlank()) {
                        ctx.append('\n').append(shorten(item.getDescription(), 1500));
                    } else if (run.getPromptSeed() != null) {
                        ctx.append('\n').append(shorten(run.getPromptSeed(), 1500));
                    }
                }

                approvalRepository.save(Approval.builder()
                        .runId(runId)
                        .status(ApprovalStatus.PENDING)
                        // Generic governance category on purpose: this ask is a
                        // kanban HITL entry, not the SDD spec gate. Typing it
                        // SPEC_REVIEW made SpecReviewCoordinator's idempotency
                        // guard and every "pending spec approval for run" lookup
                        // mistake it for the gate, stalling chains. Human-only
                        // decision is enforced on askType=REVIEW_REQUEST instead.
                        .approvalType(Approval.ApprovalType.TOOL_CALL)
                        .askType(Approval.AskType.REVIEW_REQUEST)
                        .kanbanItemId(item.getId())
                        .content(content)
                        .contextMd(ctx.toString())
                        .build());
                log.info("Auto-created REVIEW_REQUEST ask for card {} (run {})", item.getId(), runId);
            });
        } catch (Exception e) {
            log.warn("Auto REVIEW_REQUEST ask failed for {}: {}", event.getItemId(), e.getMessage());
        }
    }

    private static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
