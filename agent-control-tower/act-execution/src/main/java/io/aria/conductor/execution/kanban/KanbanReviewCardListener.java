package io.aria.conductor.execution.kanban;

import io.aria.conductor.common.event.ApprovalRequestedEvent;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.execution.repository.ApprovalRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Surfaces every approval as a Review-column card (spec 4.3): links the ask to
 * the card already associated with the run, or creates a REVIEW card for
 * orphan approvals.
 */
@Slf4j
@Component
public class KanbanReviewCardListener {

    private final ApprovalRepository approvalRepository;
    private final KanbanRepository kanbanRepository;
    private final KanbanService kanbanService;

    public KanbanReviewCardListener(ApprovalRepository approvalRepository,
                                    KanbanRepository kanbanRepository,
                                    KanbanService kanbanService) {
        this.approvalRepository = approvalRepository;
        this.kanbanRepository = kanbanRepository;
        this.kanbanService = kanbanService;
    }

    @EventListener
    @Transactional
    public void onApprovalRequested(ApprovalRequestedEvent event) {
        approvalRepository.findById(event.getApprovalId()).ifPresent(approval -> {
            if (approval.getKanbanItemId() != null) return;

            kanbanRepository.findByLinkedRunId(event.getRunId().toString()).stream()
                    .filter(card -> card.getStatus() == KanbanStatus.REVIEW
                            || card.getStatus() == KanbanStatus.IN_PROGRESS
                            || card.getStatus() == KanbanStatus.TODO)
                    .findFirst()
                    .ifPresentOrElse(
                            card -> approval.setKanbanItemId(card.getId()),
                            () -> createCard(approval, event));
            approvalRepository.save(approval);
        });
    }

    private void createCard(Approval approval, ApprovalRequestedEvent event) {
        String title = "Review: " + (approval.getApprovalType() != null
                ? approval.getApprovalType().name().toLowerCase().replace('_', ' ')
                : "approval") + " (run " + event.getRunId().toString().substring(0, 8) + ")";
        KanbanItem card = kanbanService.create(CreateKanbanItemRequest.builder()
                .title(title)
                .description(approval.getContent())
                .status(KanbanStatus.REVIEW)
                .linkedRunId(event.getRunId().toString())
                .build());
        approval.setKanbanItemId(card.getId());
        log.info("Auto-created review card {} for orphan approval {}", card.getId(), approval.getId());
    }
}
