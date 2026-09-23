package io.aria.conductor.common.repository;

import io.aria.conductor.common.model.AcpPermissionRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Run-scoped permission ledger access (spec §6.3). The correlation finder is the
 * only way an ask is looked up, so a decision can never be applied to a request
 * the caller merely claims; the bulk delete is the run-purge registration that
 * retirement consumes (a purged run must not leave permission rows behind).
 */
public interface AcpPermissionRequestRepository extends JpaRepository<AcpPermissionRequest, UUID> {

    Optional<AcpPermissionRequest> findByRunIdAndSessionIdAndRequestId(UUID runId, String sessionId, String requestId);

    Optional<AcpPermissionRequest> findByApprovalId(UUID approvalId);

    List<AcpPermissionRequest> findByRunId(UUID runId);

    /** Housekeeping/retirement: set-based delete of every ledger row of the purged runs. */
    @Modifying
    @Query("DELETE FROM AcpPermissionRequest p WHERE p.runId IN :ids")
    int deleteByRunIdInBulk(@Param("ids") List<UUID> ids);
}
