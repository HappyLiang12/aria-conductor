package io.aria.conductor.knowledge.repository;

import io.aria.conductor.common.model.KnowledgeItem;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.KnowledgeType;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
