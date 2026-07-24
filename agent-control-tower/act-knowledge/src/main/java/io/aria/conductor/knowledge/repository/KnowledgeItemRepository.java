package io.aria.conductor.knowledge.repository;

import io.aria.conductor.common.model.KnowledgeItem;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.KnowledgeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
