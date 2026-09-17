package io.aria.conductor.common.repository;

import io.aria.conductor.common.model.AcpPermissionRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface AcpPermissionRequestRepository extends JpaRepository<AcpPermissionRequest, UUID> {

    /** Batch lookup for the DTO list path (ApprovalQueryService) — avoids an N+1. */
    List<AcpPermissionRequest> findByApprovalIdIn(Collection<UUID> ids);

    /** Housekeeping S1: single-statement bulk delete (set-based, no entity load). */
    @Modifying
    @Query("DELETE FROM AcpPermissionRequest p WHERE p.runId IN :ids")
    int deleteByRunIdInBulk(@Param("ids") List<UUID> ids);
}
