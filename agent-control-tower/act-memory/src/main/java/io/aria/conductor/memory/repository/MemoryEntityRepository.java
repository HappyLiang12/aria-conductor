package io.aria.conductor.memory.repository;

import io.aria.conductor.memory.model.EntityStatus;
import io.aria.conductor.memory.model.MemoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface MemoryEntityRepository extends JpaRepository<MemoryEntity, UUID> {

    /**
     * Recall entry point: the entities the mention extractor matched, restricted to one
     * lifecycle status. A retired or merged entity never contributes memory.
     */
    List<MemoryEntity> findByCanonicalKeyInAndStatus(Collection<String> canonicalKeys, EntityStatus status);

    /**
     * Backfill entry point: every entity of one kind, restricted to one lifecycle status —
     * used to look up the AGENT/REPO/PROJECT rows the backfill reconciles.
     */
    List<MemoryEntity> findByKindAndStatus(String kind, EntityStatus status);
}
