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

    @Query("SELECT COUNT(n) FROM AriaNotificationEntity n WHERE n.isRead = false")
    long countUnread();

    @Modifying
    @Query("UPDATE AriaNotificationEntity n SET n.isRead = true WHERE n.isRead = false")
    int markAllRead();

    @Modifying
    @Query("UPDATE AriaNotificationEntity n SET n.isRead = true WHERE n.id = :id")
    int markRead(@Param("id") String id);
}
