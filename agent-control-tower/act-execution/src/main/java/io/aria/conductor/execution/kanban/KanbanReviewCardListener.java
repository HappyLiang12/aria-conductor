package io.aria.conductor.execution.kanban;

import io.aria.conductor.common.event.ApprovalRequestedEvent;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.execution.repository.ApprovalRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Surfaces every approval as a Review-column card (spec 4.3): links the ask to
 * the card already associated with the run, or creates a REVIEW card for
 * orphan approvals.
 *
 * <p>Runs after the requesting transaction commits, in its own transaction: the
 * approval is recorded before the board is touched, and a failure here must
 * neither roll back the approval that triggered it nor surface to the caller
 * that recorded it. ApprovalGate raises asks outside any transaction, so the
 * listener must fall back to running immediately for those; without it the ask
 * would silently get no card.
 *
 * <p>The linking work sits inside a {@link TransactionTemplate} instead of a
 * {@code @Transactional} method so the catch can sit outside the transaction
 * boundary. A failure caught inside it — say a run link {@code create} refuses —
 * leaves that transaction rollback-only, and its commit then throws
 * {@code UnexpectedRollbackException} from the proxy, past the catch and into
 * the event multicaster. The multicaster stops dispatching on a throw, so that
 * would silently skip every listener registered after this one.
 */
@Slf4j
@Component
public class KanbanReviewCardListener {

    private final ApprovalRepository approvalRepository;
    private final KanbanRepository kanbanRepository;
    private final KanbanService kanbanService;
    private final TransactionTemplate reviewTransaction;

    public KanbanReviewCardListener(ApprovalRepository approvalRepository,
                                    KanbanRepository kanbanRepository,
                                    KanbanService kanbanService,
                                    PlatformTransactionManager transactionManager) {
        this.approvalRepository = approvalRepository;
        this.kanbanRepository = kanbanRepository;
        this.kanbanService = kanbanService;
        this.reviewTransaction = new TransactionTemplate(transactionManager);
        this.reviewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onApprovalRequested(ApprovalRequestedEvent event) {
        // Defensive mirroring of RunKanbanAutoCreator: a listener must never
        // break the publisher's transaction.
        if (event.getRunId() == null) {
            log.warn("ApprovalRequestedEvent without runId (approval {}) — skipping review-card linkage",
                    event.getApprovalId());
            return;
        }
        try {
            reviewTransaction.executeWithoutResult(status -> linkReviewCard(event));
        } catch (Exception e) {
            log.warn("Failed to surface review card for approval {}: {}",
                    event.getApprovalId(), e.getMessage());
        }
    }

    private void linkReviewCard(ApprovalRequestedEvent event) {
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
