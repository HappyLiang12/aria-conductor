package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.repository.RunRepository;
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
 * ask so the Review column always surfaces a structured decision surface.
 * Idempotent: skipped when a PENDING ask already exists on the card.
 * DB-level failures on save surface at commit time and roll the whole transition
 * back (all-or-nothing by design).
 */
@Slf4j
@Component
public class KanbanReviewAskCreator {

    private final ApprovalRepository approvalRepository;
    private final KanbanRepository kanbanRepository;
    private final RunRepository runRepository;

    public KanbanReviewAskCreator(ApprovalRepository approvalRepository,
                                  KanbanRepository kanbanRepository,
                                  RunRepository runRepository) {
        this.approvalRepository = approvalRepository;
        this.kanbanRepository = kanbanRepository;
        this.runRepository = runRepository;
    }

    @EventListener
    @Transactional
    public void onKanbanItemTransitioned(KanbanItemTransitionedEvent event) {
        if (!"IN_PROGRESS".equals(event.getFromStatus()) || !"REVIEW".equals(event.getToStatus())) {
            return;
        }
        try {
            kanbanRepository.findById(event.getItemId()).ifPresent(item -> {
                if (!approvalRepository.findByStatusAndKanbanItemId(
                        ApprovalStatus.PENDING, item.getId()).isEmpty()) {
                    return;
                }
                if (item.getLinkedRunId() == null || item.getLinkedRunId().isBlank()) {
                    return; // Approval.runId is NOT NULL - nothing to attach to
                }
                UUID runId = UUID.fromString(item.getLinkedRunId());
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
                        .approvalType(Approval.ApprovalType.SPEC_REVIEW)
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
