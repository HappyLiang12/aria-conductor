package io.aria.conductor.execution.repository;

import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface ApprovalRepository extends JpaRepository<Approval, UUID> {
    List<Approval> findByStatus(ApprovalStatus status);
    List<Approval> findByRunId(UUID runId);
    List<Approval> findByStatusAndExpiresAtBefore(ApprovalStatus status, Instant expiresAtBefore);
    List<Approval> findByStatusAndApprovalType(ApprovalStatus status, Approval.ApprovalType type);

    /** Housekeeping S1: single-statement bulk delete (set-based, no entity load). */
    @Modifying
    @Query("DELETE FROM Approval a WHERE a.runId IN :ids")
    int deleteByRunIdInBulk(@Param("ids") List<UUID> ids);
}