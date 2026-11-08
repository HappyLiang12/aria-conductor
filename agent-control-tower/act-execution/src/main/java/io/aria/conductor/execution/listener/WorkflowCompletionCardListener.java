package io.aria.conductor.execution.listener;

import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.event.WorkflowAdvancedEvent;
import io.aria.conductor.common.event.WorkflowCancelledEvent;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.execution.kanban.KanbanItem;
import io.aria.conductor.execution.kanban.KanbanRepository;
import io.aria.conductor.execution.kanban.KanbanStatus;
import io.aria.conductor.execution.kanban.KanbanTransitionService;
import io.aria.conductor.execution.kanban.TransitionRequest;
import io.aria.conductor.execution.repository.ApprovalRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

/**
 * F24: when a workflow chain completes, its finished step cards leave REVIEW for
 * DONE. The live run left cards 9d57be41 (BA), d3e7f97c (Dev) and c61d0bb7 (QA) in
 * REVIEW with {@code pendingAskCount = 0} after the chain reached COMPLETED, so the
 * board showed finished work as awaiting a review nobody could perform.
 *
 * <p>REVIEW therefore means "needs a human": a card that still carries a PENDING ask
 * stays where it is — that decision surface is real, and the card's derived
 * {@code runOutcome} is what tells the reader the run finished (COMPLETED). The move
 * goes through {@link KanbanTransitionService} so the DONE guard and the linked-ask
 * sweep are the operator path's, not a second writer's.
 *
 * <p>A chain that ends FAILED or CANCELLED settles its ask-less REVIEW cards to
 * CANCELLED: nobody can perform the review a dead chain was waiting for.
 *
 * <p>R6: a manual cancel publishes {@link WorkflowCancelledEvent}, not a terminal
 * {@link WorkflowAdvancedEvent}, and the in-flight run's card only reaches REVIEW after
 * its run is finalized — i.e. after the cancel. So {@link #onWorkflowCancelled} settles
 * the cancelled chain's cards itself: a cancelled chain owns its cards, and leaving the
 * in-flight card IN_PROGRESS would show live work for a dead chain. Every step card in
 * IN_PROGRESS or REVIEW moves to CANCELLED, with no pending-ask exception: the cancel is
 * the operator's explicit stop, and
 * {@code KanbanTransitionService.transition(card, CANCELLED)} already denies that card's
 * pending asks and stops its live run through the operator path. Settling at cancel time
 * closes the "card reaches REVIEW after the chain is already terminal" residual because
 * {@code RunKanbanAutoCreator.onRunCompleted} skips cards already DONE/CANCELLED. The
 * FAILED path keeps its narrower rule: ask-less REVIEW cards only.
 *
 * <p>Ordering: {@code RunKanbanAutoCreator.onRunCompleted} runs before
 * {@code WorkflowAutoChainer.onRunCompleted} ({@code @Order} 0 vs 100), so the
 * step card's REVIEW move is queued on the single-thread
 * {@code kanbanMirrorExecutor} before this listener queues its settle behind it;
 * the card is already in REVIEW when it is settled. The work runs in its own
 * transaction, and the catch sits outside that boundary, so a refused transition
 * can never travel back into the event multicaster.
 */
@Slf4j
@Component
public class WorkflowCompletionCardListener {

    /** Operator-facing audit comment on the card the chain closed out. */
    static final String COMPLETION_COMMENT = "workflow chain completed";
    static final String FAILURE_COMMENT = "workflow chain failed";
    static final String CANCELLATION_COMMENT = "workflow chain cancelled";

    private final WorkflowService workflowService;
    private final KanbanRepository kanbanRepository;
    private final ApprovalRepository approvalRepository;
    private final KanbanTransitionService kanbanTransitionService;
    private final TransactionTemplate settleTransaction;
    private final Executor mirrorExecutor;

    public WorkflowCompletionCardListener(WorkflowService workflowService,
                                          KanbanRepository kanbanRepository,
                                          ApprovalRepository approvalRepository,
                                          KanbanTransitionService kanbanTransitionService,
                                          PlatformTransactionManager transactionManager,
                                          @Qualifier("kanbanMirrorExecutor") Executor mirrorExecutor) {
        this.workflowService = workflowService;
        this.kanbanRepository = kanbanRepository;
        this.approvalRepository = approvalRepository;
        this.kanbanTransitionService = kanbanTransitionService;
        this.settleTransaction = new TransactionTemplate(transactionManager);
        this.settleTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.mirrorExecutor = mirrorExecutor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onWorkflowCompleted(WorkflowAdvancedEvent event) {
        KanbanStatus target = switch (event.getChainStatus()) {
            case COMPLETED -> KanbanStatus.DONE;
            // A dead chain leaves no review a human can perform: settle the
            // ask-less REVIEW cards out of the column, with a truthful comment.
            case FAILED -> KanbanStatus.CANCELLED;
            case CANCELLED -> KanbanStatus.CANCELLED;
            default -> null;
        };
        if (target == null) {
            return;
        }
        String comment = switch (target) {
            case DONE -> COMPLETION_COMMENT;
            default -> event.getChainStatus() == WorkflowChain.Status.CANCELLED
                    ? CANCELLATION_COMMENT : FAILURE_COMMENT;
        };
        submitMirror(() -> {
            try {
                settleTransaction.executeWithoutResult(status ->
                        settleStepCards(event.getWorkflowId(), target, comment, this::isSettleable));
            } catch (RuntimeException e) {
                log.warn("Failed to settle the step cards of {} chain {}: {}",
                        event.getChainStatus(), event.getWorkflowId(), e.getMessage());
            }
        });
    }

    /**
     * R6: a cancelled chain owns its cards, and its in-flight run's card only reaches
     * REVIEW after the cancel event, so waiting for a terminal
     * {@link WorkflowAdvancedEvent} would leave it unsettled forever. Every step card
     * still IN_PROGRESS or REVIEW is settled here; a pending ask does not save the card,
     * because the operator's cancel is the explicit stop and the transition denies the
     * ask and stops the run through the operator path.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onWorkflowCancelled(WorkflowCancelledEvent event) {
        submitMirror(() -> {
            try {
                settleTransaction.executeWithoutResult(status ->
                        settleStepCards(event.getWorkflowId(), KanbanStatus.CANCELLED,
                                CANCELLATION_COMMENT,
                                card -> card.getStatus() == KanbanStatus.IN_PROGRESS
                                        || card.getStatus() == KanbanStatus.REVIEW));
            } catch (RuntimeException e) {
                log.warn("Failed to settle the step cards of cancelled chain {}: {}",
                        event.getWorkflowId(), e.getMessage());
            }
        });
    }

    /**
     * R6: settles every card the chain's steps were dispatched with — each step's
     * superseded attempts first, then its current run — because a DEFECT/SPEC_GAP
     * reschedule overwrites {@code step.runId}. Reading only the current run id left
     * the superseded attempts' REVIEW cards unreachable forever.
     */
    private void settleStepCards(UUID chainId, KanbanStatus target, String comment,
                                 Predicate<KanbanItem> settleable) {
        for (UUID runId : workflowService.stepRunIds(chainId)) {
            if (runId == null) {
                continue;
            }
            List<KanbanItem> cards = kanbanRepository.findByLinkedRunId(runId.toString());
            for (KanbanItem card : cards) {
                if (!settleable.test(card)) {
                    continue;
                }
                kanbanTransitionService.transition(card.getId(), TransitionRequest.builder()
                        .status(target)
                        .comment(comment)
                        .build());
                log.info("Settled card {} of finished chain {} to {} (run {})",
                        card.getId(), chainId, target, runId);
            }
        }
    }

    /**
     * Completion/failure rule: only a REVIEW card with no open ask is ours to close;
     * anything else is human state.
     */
    private boolean isSettleable(KanbanItem card) {
        if (card.getStatus() != KanbanStatus.REVIEW) {
            return false;
        }
        return approvalRepository
                .findByStatusAndKanbanItemId(ApprovalStatus.PENDING, card.getId())
                .isEmpty();
    }

    /**
     * A rejected submission (the executor is shutting down) must not travel back into
     * the event multicaster: it stops dispatching on a throw, which would silence every
     * listener registered after this one.
     */
    private void submitMirror(Runnable work) {
        try {
            mirrorExecutor.execute(work);
        } catch (RuntimeException e) {
            log.warn("Workflow completion card settle could not be scheduled: {}", e.getMessage());
        }
    }
}
