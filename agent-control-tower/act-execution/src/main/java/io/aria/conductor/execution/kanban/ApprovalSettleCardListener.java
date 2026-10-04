package io.aria.conductor.execution.kanban;

import io.aria.conductor.common.event.ApprovalDecidedEvent;
import io.aria.conductor.common.event.ApprovalExpiredEvent;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalSource;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.execution.repository.ApprovalRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Settle propagation (review-flow amendment, D6): when a native permission ask
 * settles (decided or expired), its auto-created review card reaches a terminal
 * state instead of lingering. Only native asks are handled: review asks are the
 * card layer's own vocabulary and keep their existing flows.
 *
 * <p>Both handlers run {@code AFTER_COMMIT}: a settle must only propagate from a
 * decision that actually committed, and the guarded transition must be able to
 * see that commitment — {@link KanbanService}'s DONE guard exempts cards whose
 * linked native ask has settled (R-RFUX3), and the deciding transaction has
 * published this event before its row update commits. Running at the publish
 * point instead would read the uncommitted row as PENDING and refuse the very
 * settle this listener exists to perform. {@code fallbackExecution} keeps
 * publishers outside any transaction on the immediate path.
 *
 * <p>Best-effort like {@link KanbanReviewCardListener}: the settle runs in its
 * own transaction via a {@link TransactionTemplate} and the catch sits outside
 * that transaction boundary, so a refusal — say the linked run is still active —
 * rolls back only the settle and is logged; it can never travel back into the
 * decision path that published the event.
 */
@Slf4j
@Component
public class ApprovalSettleCardListener {

    private final ApprovalRepository approvals;
    private final KanbanRepository kanban;
    private final KanbanService kanbanService;
    private final TransactionTemplate settleTransaction;

    public ApprovalSettleCardListener(ApprovalRepository approvals, KanbanRepository kanban,
            KanbanService kanbanService, PlatformTransactionManager transactionManager) {
        this.approvals = approvals;
        this.kanban = kanban;
        this.kanbanService = kanbanService;
        this.settleTransaction = new TransactionTemplate(transactionManager);
        this.settleTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDecided(ApprovalDecidedEvent event) {
        settle(event.getApprovalId(),
                event.getDecision() == ApprovalStatus.APPROVED ? KanbanStatus.DONE : KanbanStatus.CANCELLED,
                event.getDecision() == ApprovalStatus.APPROVED ? "ask approved" : "ask denied");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onExpired(ApprovalExpiredEvent event) {
        settle(event.getApprovalId(), KanbanStatus.CANCELLED, "ask expired");
    }

    private void settle(UUID approvalId, KanbanStatus target, String comment) {
        try {
            settleTransaction.executeWithoutResult(status -> {
                Approval approval = approvals.findById(approvalId).orElse(null);
                if (approval == null || approval.getSource() != ApprovalSource.ACP_PERMISSION
                        || approval.getKanbanItemId() == null) {
                    return;
                }
                KanbanItem card = kanban.findById(approval.getKanbanItemId()).orElse(null);
                if (card == null || card.getStatus() != KanbanStatus.REVIEW) {
                    return; // the operator already moved it; never override a human action
                }
                kanbanService.transition(card.getId(), target, comment);
            });
        } catch (RuntimeException e) {
            log.warn("Ask {} settled but its review card could not be settled: {}", approvalId, e.getMessage());
        }
    }
}
