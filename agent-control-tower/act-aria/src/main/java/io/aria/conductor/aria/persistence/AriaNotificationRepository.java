package io.aria.conductor.aria.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AriaNotificationRepository extends JpaRepository<AriaNotificationEntity, String> {

    Page<AriaNotificationEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Dedupe guard for the dispatch-batch completion wake, scoped PER dispatch group: the group id
     * is written verbatim into the notification body ({@code Batch <dispatchedByRunId>: ...}), so
     * matching on the body fragment keeps a replayed completion of an already-notified group
     * suppressed while a later batch in the same conversation notifies again.
     */
    boolean existsByTypeAndResourceIdAndBodyContaining(String type, String resourceId, String fragment);

    @Query("SELECT COUNT(n) FROM AriaNotificationEntity n WHERE n.isRead = false")
    long countUnread();

    @Modifying
    @Query("UPDATE AriaNotificationEntity n SET n.isRead = true WHERE n.isRead = false")
    int markAllRead();

    @Modifying
    @Query("UPDATE AriaNotificationEntity n SET n.isRead = true WHERE n.id = :id")
    int markRead(@Param("id") String id);

    /**
     * D6: a settled ask (decided or expired) flips its own
     * "waiting for your decision" notification back to read. Scoped by the
     * approval id written verbatim into {@code resourceId} at creation time.
     */
    @Modifying
    @Query("UPDATE AriaNotificationEntity n SET n.isRead = true " +
           "WHERE n.type = 'approval.requested' AND n.resourceId = :resourceId AND n.isRead = false")
    int markApprovalRequestedRead(@Param("resourceId") String resourceId);
}
