package io.aria.conductor.execution.repository;

import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalSource;
import io.aria.conductor.common.model.ApprovalStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface ApprovalRepository extends JpaRepository<Approval, UUID> {
    List<Approval> findByStatus(ApprovalStatus status);
    List<Approval> findByRunId(UUID runId);
    List<Approval> findByStatusAndExpiresAtBefore(ApprovalStatus status, Instant expiresAtBefore);
    List<Approval> findByStatusAndApprovalType(ApprovalStatus status, Approval.ApprovalType type);

    /** Source-scoped scan: the ACP startup recovery walks only {@code ACP_PERMISSION} rows. */
    List<Approval> findByStatusAndSource(ApprovalStatus status, ApprovalSource source);

    /**
     * Atomic PENDING → EXPIRED transition for the ACP expiry path: only the caller whose
     * conditional update matches proceeds to deliver the cancel, so a decision that landed
     * first can never be rewritten (returns 0 for it). Single-statement bulk update, no
     * entity load; callers must be @Transactional and results bypass the persistence context.
     */
    @Modifying
    @Query("update Approval a set a.status = io.aria.conductor.common.model.ApprovalStatus.EXPIRED, " +
           "a.reason = :reason, a.decidedAt = :now " +
           "where a.id = :id and a.status = io.aria.conductor.common.model.ApprovalStatus.PENDING")
    int expirePendingById(@Param("id") UUID id, @Param("reason") String reason, @Param("now") Instant now);

    /** HITL ask surface: asks linked to a kanban card. */
    List<Approval> findByKanbanItemId(String kanbanItemId);

    List<Approval> findByStatusAndKanbanItemId(ApprovalStatus status, String kanbanItemId);

    @Query("select a.kanbanItemId, count(a) from Approval a " +
           "where a.status = io.aria.conductor.common.model.ApprovalStatus.PENDING " +
           "and a.kanbanItemId in :ids group by a.kanbanItemId")
    List<Object[]> countPendingByKanbanItemIds(@Param("ids") Collection<String> ids);

    /** Bulk deny of a card's PENDING asks (work cancelled): single-statement
     *  bulk update, no entity load; callers must be @Transactional and results
     *  bypass the persistence context. */
    @Modifying
    @Query("update Approval a set a.status = io.aria.conductor.common.model.ApprovalStatus.DENIED, " +
           "a.reason = :reason, a.decidedAt = :now " +
           "where a.kanbanItemId = :itemId and a.status = io.aria.conductor.common.model.ApprovalStatus.PENDING")
    int denyPendingByKanbanItemId(@Param("itemId") String itemId,
                                  @Param("reason") String reason,
                                  @Param("now") Instant now);

    /** Marks PENDING asks on a card stale (EXPIRED) when the operator sends
     *  the work back with changes. Single-statement bulk update, no entity
     *  load; callers must be @Transactional and results bypass the persistence
     *  context. Represents the spec's CHANGES_REQUESTED ask state via
     *  EXPIRED + reason (ApprovalStatus has no dedicated value). */
    @Modifying
    @Query("update Approval a set a.status = io.aria.conductor.common.model.ApprovalStatus.EXPIRED, " +
           "a.reason = 'superseded by request changes', a.decidedAt = :now " +
           "where a.kanbanItemId = :itemId and a.status = io.aria.conductor.common.model.ApprovalStatus.PENDING")
    int markStaleByKanbanItemId(@Param("itemId") String itemId, @Param("now") Instant now);

    /** Housekeeping S1: single-statement bulk delete (set-based, no entity load). */
    @Modifying
    @Query("DELETE FROM Approval a WHERE a.runId IN :ids")
    int deleteByRunIdInBulk(@Param("ids") List<UUID> ids);
}