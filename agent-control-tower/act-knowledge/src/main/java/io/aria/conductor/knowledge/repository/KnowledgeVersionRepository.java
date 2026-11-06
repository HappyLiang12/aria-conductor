package io.aria.conductor.knowledge.repository;

import io.aria.conductor.common.model.KnowledgeVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KnowledgeVersionRepository extends JpaRepository<KnowledgeVersion, UUID> {

    List<KnowledgeVersion> findByKnowledgeItemIdOrderByCreatedAtDesc(UUID itemId);

    Optional<KnowledgeVersion> findByKnowledgeItemIdAndVersion(UUID itemId, String version);
}
