package io.aria.conductor.execution.kanban;

import io.aria.conductor.common.event.KanbanItemCreatedEvent;
import io.aria.conductor.common.exception.PickupRejectedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Defect D1 (spec D3): a card created directly in Todo is a dispatch intent —
 * Aria must assign an agent and start a run immediately instead of leaving the
 * card parked until someone drags it.
 *
 * <p>Runs for every creation surface (REST, Aria tools, MCP) because it reacts
 * to the domain event rather than a specific controller. Backlog cards stay
 * queued.
 *
 * <p>Runs AFTER the creator's transaction commits, in its own transaction. The
 * card is therefore durable before the pickup is attempted, and a failed pickup
 * can neither roll it back nor poison the creator with a rollback-only
 * transaction. Because the attempt is isolated in a further transaction, the
 * rejection it raises cannot mark this listener's transaction rollback-only —
 * which is what would otherwise discard the very {@code lastError} being
 * recorded. A rejection has no request left to answer, so lastError on the card
 * face is the answer. (A synchronous operator move is the opposite: its
 * rejection is a 4xx and writes no {@code lastError}.)
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "aria.kanban.auto-dispatch-on-create", havingValue = "true", matchIfMissing = true)
public class KanbanAutoDispatchListener {

    /** kanban_items.last_error is 500 chars: a longer reason would fail the very save that records it. */
    private static final int MAX_LAST_ERROR_CHARS = 500;

    private final KanbanRepository kanbanRepository;
    private final KanbanTransitionService kanbanTransitionService;
    private final TransactionTemplate pickupAttempt;

    public KanbanAutoDispatchListener(KanbanRepository kanbanRepository,
                                      KanbanTransitionService kanbanTransitionService,
                                      PlatformTransactionManager transactionManager) {
        this.kanbanRepository = kanbanRepository;
        this.kanbanTransitionService = kanbanTransitionService;
        this.pickupAttempt = new TransactionTemplate(transactionManager);
        this.pickupAttempt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onKanbanItemCreated(KanbanItemCreatedEvent event) {
        try {
            kanbanRepository.findById(event.getItemId()).ifPresent(item -> {
                if (item.getStatus() != KanbanStatus.TODO) {
                    return; // Backlog is a queue; other statuses are explicit placements.
                }
                // Run-managed cards (auto-created by RunKanbanAutoCreator for a run
                // that already exists) describe that run — dispatching them would
                // create a duplicate second run for the same agent. Only cards
                // created WITHOUT a run (operator/Aria intent) are dispatch intents.
                if (!isBlank(item.getLinkedRunId())) {
                    return;
                }
                // Isolated from this listener's transaction: a rejected pickup
                // would otherwise leave it rollback-only, so the lastError below
                // would be written into a transaction that can never commit.
                pickupAttempt.executeWithoutResult(status ->
                        kanbanTransitionService.dispatch(item.getId(), item.getAgentTemplateId()));
            });
        } catch (Exception e) {
            // Creation must never fail because the pickup could not start.
            log.warn("Auto-dispatch on create failed for {}: {}", event.getItemId(), e.getMessage());
            recordFailure(event.getItemId(), e);
        }
    }

    private void recordFailure(String itemId, Exception failure) {
        try {
            kanbanRepository.findById(itemId).ifPresent(item -> {
                item.setLastError(describe(failure));
                kanbanRepository.save(item);
            });
        } catch (Exception e) {
            // This listener runs after the publisher committed, so an escaping
            // exception would still read as a failed create.
            log.warn("Could not record pickup failure on {}: {}", itemId, e.getMessage());
        }
    }

    private static String describe(Exception failure) {
        String reason = failure instanceof PickupRejectedException rejected
                ? rejected.code() + ": " + rejected.getMessage()
                : String.valueOf(failure.getMessage());
        return reason.length() > MAX_LAST_ERROR_CHARS ? reason.substring(0, MAX_LAST_ERROR_CHARS) : reason;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
