package io.aria.conductor.execution.repository;

import io.aria.conductor.common.model.Approval;
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

    /** HITL ask surface: asks linked to a kanban card. */
    List<Approval> findByKanbanItemId(String kanbanItemId);

    List<Approval> findByStatusAndKanbanItemId(ApprovalStatus status, String kanbanItemId);

    @Query("select a.kanbanItemId, count(a) from Approval a " +
           "where a.status = io.aria.conductor.common.model.ApprovalStatus.PENDING " +
           "and a.kanbanItemId in :ids group by a.kanbanItemId")
    List<Object[]> countPendingByKanbanItemIds(@Param("ids") Collection<String> ids);

    @Modifying
    @Query("update Approval a set a.status = io.aria.conductor.common.model.ApprovalStatus.DENIED, " +
           "a.reason = :reason, a.decidedAt = :now " +
           "where a.kanbanItemId = :itemId and a.status = io.aria.conductor.common.model.ApprovalStatus.PENDING")
    int denyPendingByKanbanItemId(@Param("itemId") String itemId,
                                  @Param("reason") String reason,
                                  @Param("now") Instant now);

    @Modifying
    @Query("update Approval a set a.status = io.aria.conductor.common.model.ApprovalStatus.EXPIRED, " +
           "a.reason = 'superseded by request changes' " +
           "where a.kanbanItemId = :itemId and a.status = io.aria.conductor.common.model.ApprovalStatus.PENDING")
    int markStaleByKanbanItemId(@Param("itemId") String itemId);

    /** Housekeeping S1: single-statement bulk delete (set-based, no entity load). */
    @Modifying
    @Query("DELETE FROM Approval a WHERE a.runId IN :ids")
    int deleteByRunIdInBulk(@Param("ids") List<UUID> ids);
}