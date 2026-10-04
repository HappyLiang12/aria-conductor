package io.aria.conductor.execution.kanban;

import io.aria.conductor.common.event.ApprovalRequestedEvent;
import io.aria.conductor.common.model.AcpPermissionRequest;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.repository.AcpPermissionRequestRepository;
import io.aria.conductor.execution.repository.ApprovalRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * Surfaces every pending approval as a Review-column card (spec 4.3): links the
 * ask to the card already associated with the run, or creates a REVIEW card for
 * orphan approvals. An ask that already settled while the mirror was in flight
 * is skipped: its card could never be settled again.
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
 *
 * <p>The work runs on the {@code kanbanMirrorExecutor} rather than on the
 * publisher's thread: an after-commit listener still runs before Spring releases
 * the publisher's connection, so linking there would ask the pool for a second
 * connection on a thread that already holds one.
 */
@Slf4j
@Component
public class KanbanReviewCardListener {

    private final ApprovalRepository approvalRepository;
    private final KanbanRepository kanbanRepository;
    private final KanbanService kanbanService;
    private final AcpPermissionRequestRepository permissions;
    private final TransactionTemplate reviewTransaction;
    private final Executor mirrorExecutor;

    public KanbanReviewCardListener(ApprovalRepository approvalRepository,
                                    KanbanRepository kanbanRepository,
                                    KanbanService kanbanService,
                                    AcpPermissionRequestRepository permissions,
                                    PlatformTransactionManager transactionManager,
                                    @Qualifier("kanbanMirrorExecutor") Executor mirrorExecutor) {
        this.approvalRepository = approvalRepository;
        this.kanbanRepository = kanbanRepository;
        this.kanbanService = kanbanService;
        this.permissions = permissions;
        this.reviewTransaction = new TransactionTemplate(transactionManager);
        this.reviewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.mirrorExecutor = mirrorExecutor;
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
        submitMirror(() -> {
            try {
                reviewTransaction.executeWithoutResult(status -> linkReviewCard(event));
            } catch (Exception e) {
                log.warn("Failed to surface review card for approval {}: {}",
                        event.getApprovalId(), e.getMessage());
            }
        });
    }

    /**
     * A rejected submission (the executor is shutting down) must not travel
     * back into the event multicaster: it stops dispatching on a throw, which
     * would silence every listener registered after this one.
     */
    private void submitMirror(Runnable work) {
        try {
            mirrorExecutor.execute(work);
        } catch (RuntimeException e) {
            log.warn("Kanban mirroring could not be scheduled: {}", e.getMessage());
        }
    }

    /**
     * Links the ask by writing only the {@code kanban_item_id} column, guarded
     * on it still being null — never by saving the loaded entity. The approval
     * is read while it may still be PENDING and the operator's decision can
     * commit while this mirror is in flight: a full-row write from the stale
     * snapshot would revert a settled decision to PENDING (a lost update the
     * decision path's row lock cannot prevent), and a further decision could
     * then re-arm an already consumed one-use grant. The entity is neither
     * mutated nor saved, so no dirty snapshot can flush over the decision.
     *
     * <p>A settled ask is skipped before anything is read or written: a second
     * decision is refused, so a card created or linked now could never be
     * settled again — it would linger in REVIEW forever.
     */
    private void linkReviewCard(ApprovalRequestedEvent event) {
        approvalRepository.findById(event.getApprovalId()).ifPresent(approval -> {
            if (approval.getStatus() != ApprovalStatus.PENDING) return;
            if (approval.getKanbanItemId() != null) return;

            kanbanRepository.findByLinkedRunId(event.getRunId().toString()).stream()
                    .filter(card -> card.getStatus() == KanbanStatus.REVIEW
                            || card.getStatus() == KanbanStatus.IN_PROGRESS
                            || card.getStatus() == KanbanStatus.TODO)
                    .findFirst()
                    .ifPresentOrElse(
                            card -> approvalRepository.linkKanbanItemIdIfAbsent(approval.getId(), card.getId()),
                            () -> createCard(approval, event));
        });
    }

    private void createCard(Approval approval, ApprovalRequestedEvent event) {
        // A native ask knows its tool and arguments (D7): its card must say what
        // the operator is deciding, not just "tool call". Review asks keep the
        // readable approval-type title; they have no tool correlation.
        Optional<AcpPermissionRequest> permission = permissions.findByApprovalId(approval.getId());
        String title;
        String description;
        if (permission.isPresent()) {
            title = "Review: tool call - " + permission.get().getToolName()
                    + " (run " + approval.getRunId().toString().substring(0, 8) + ")";
            description = argumentExcerpt(permission.get().getArgumentsJson());
        } else {
            title = "Review: " + (approval.getApprovalType() != null
                    ? approval.getApprovalType().name().toLowerCase().replace('_', ' ')
                    : "approval") + " (run " + event.getRunId().toString().substring(0, 8) + ")";
            description = approval.getContent();
        }
        KanbanItem card = kanbanService.create(CreateKanbanItemRequest.builder()
                .title(title)
                .description(description)
                .status(KanbanStatus.REVIEW)
                .linkedRunId(event.getRunId().toString())
                .build());
        approvalRepository.linkKanbanItemIdIfAbsent(approval.getId(), card.getId());
        log.info("Auto-created review card {} for orphan approval {}", card.getId(), approval.getId());
    }

    /** Card-face cap on the arguments document; a null document stays null. */
    private static String argumentExcerpt(String arguments) {
        if (arguments == null) return null;
        return arguments.length() > 200 ? arguments.substring(0, 200) + "..." : arguments;
    }
}
