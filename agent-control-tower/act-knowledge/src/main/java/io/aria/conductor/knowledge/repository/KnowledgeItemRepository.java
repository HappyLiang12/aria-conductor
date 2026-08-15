package io.aria.conductor.knowledge.repository;

import io.aria.conductor.common.model.KnowledgeItem;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.KnowledgeType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KnowledgeItemRepository extends JpaRepository<KnowledgeItem, UUID> {

    List<KnowledgeItem> findByType(KnowledgeType type);

    List<KnowledgeItem> findByStatus(KnowledgeStatus status);

    List<KnowledgeItem> findByTypeAndStatus(KnowledgeType type, KnowledgeStatus status);

    List<KnowledgeItem> findByStatusAndType(KnowledgeStatus status, KnowledgeType type);

    Optional<KnowledgeItem> findByNameAndType(String name, KnowledgeType type);

    Optional<KnowledgeItem> findByName(String name);

    /**
     * Pessimistic-write lookup used by the review state machine. Acquiring a row
     * write-lock serializes concurrent reviews of the same item so two opposing
     * decisions (approve vs reject) cannot both read PENDING and both commit
     * (KnowledgeItem carries no @Version). The second reviewer blocks until the
     * first commits, then sees the decided status and is rejected by the guard.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select k from KnowledgeItem k where k.id = :id")
    Optional<KnowledgeItem> findByIdForUpdate(@Param("id") UUID id);

    long countByStatus(KnowledgeStatus status);

    List<KnowledgeItem> findByStatusAndCreatedAtBefore(KnowledgeStatus status, Instant cutoff);

    List<KnowledgeItem> findByStatusOrderByUpdatedAtDesc(KnowledgeStatus status);

    /**
     * Keyword search across name, description, and version content (#31).
     * Excludes RETIRED and REJECTED items but INCLUDES PENDING/DRAFT so freshly stored
     * knowledge is discoverable. When {@code status} is non-null, restricts to that exact
     * status (e.g. APPROVED for agent-facing query_knowledge). When {@code keyword} is null,
     * matches all items subject to the type/status filters.
     */
    @Query("select i from KnowledgeItem i "
            + "where (:keyword is null "
            + "   or lower(i.name) like :keyword "
            + "   or lower(coalesce(i.description, '')) like :keyword "
            + "   or exists (select 1 from KnowledgeVersion v "
            + "              where v.knowledgeItemId = i.id and lower(coalesce(v.content, '')) like :keyword)) "
            + "and (:type is null or i.type = :type) "
            + "and (:status is null or i.status = :status) "
            + "and i.status <> io.aria.conductor.common.model.KnowledgeStatus.RETIRED "
            + "and i.status <> io.aria.conductor.common.model.KnowledgeStatus.REJECTED")
    List<KnowledgeItem> searchByKeyword(@Param("keyword") String keyword,
                                        @Param("type") KnowledgeType type,
                                        @Param("status") KnowledgeStatus status,
                                        Pageable pageable);
}
