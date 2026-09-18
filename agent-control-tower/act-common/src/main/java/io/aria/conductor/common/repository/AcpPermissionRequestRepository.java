package io.aria.conductor.common.repository;

import io.aria.conductor.common.model.AcpPermissionRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AcpPermissionRequestRepository extends JpaRepository<AcpPermissionRequest, UUID> {

    /**
     * Correlation lookup for the ACP coordinator's dedupe path — the same pair the unique
     * constraint guards, so this is the only way to recognise a re-delivered bridge event.
     */
    Optional<AcpPermissionRequest> findByBridgeSessionIdAndBridgeRequestId(String bridgeSessionId,
                                                                           String bridgeRequestId);

    /** Batch lookup for the DTO list path (ApprovalQueryService) — avoids an N+1. */
    List<AcpPermissionRequest> findByApprovalIdIn(Collection<UUID> ids);

    /** Delivery-state scan: the startup reconciliation counts the rows its bulk update settles. */
    List<AcpPermissionRequest> findByDeliveryState(String deliveryState);

    /**
     * Startup reconciliation (M1 closure): every companion row still {@code DELIVERING} belongs to
     * a process that died mid-delivery — no worker owns it anymore and no session can be resumed,
     * so the dead delivery is settled as {@code CANCELLED}. The approval's decision record is
     * deliberately not touched: only the delivery outcome was lost. Single-statement bulk update,
     * no entity load; callers must be @Transactional and results bypass the persistence context.
     */
    @Modifying
    @Query("update AcpPermissionRequest p set p.deliveryState = 'CANCELLED' "
           + "where p.deliveryState = 'DELIVERING'")
    int markStuckDeliveringCancelled();

    /** Housekeeping S1: single-statement bulk delete (set-based, no entity load). */
    @Modifying
    @Query("DELETE FROM AcpPermissionRequest p WHERE p.runId IN :ids")
    int deleteByRunIdInBulk(@Param("ids") List<UUID> ids);
}
