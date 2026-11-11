package io.aria.conductor.memory.repository;

import io.aria.conductor.memory.model.EntityStatus;
import io.aria.conductor.memory.model.MemoryEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MemoryEntityRepository extends JpaRepository<MemoryEntity, UUID> {

    /**
     * Identity probe of the write path: {@code UNIQUE (kind, canonical_key)} is the real
     * identity (V81), so a repeated proposal resolves to the row that already owns the key
     * instead of inserting a second one.
     */
    Optional<MemoryEntity> findByKindAndCanonicalKey(String kind, String canonicalKey);

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

    /**
     * Recall's lexical universe: one bounded page of entities in one lifecycle status,
     * newest first. Display names and aliases have no index of their own, so recall needs
     * some candidate set to match lexically — this keeps that set a page instead of a
     * full-table scan on the prompt hot path.
     */
    List<MemoryEntity> findByStatusOrderByUpdatedAtDesc(EntityStatus status, Pageable page);

    /**
     * One-hop recall expansion: the neighbour rows in one lifecycle status for many ids in
     * a single query, so a recall turn does not issue one {@code findById} per neighbour.
     */
    List<MemoryEntity> findByIdInAndStatus(Collection<UUID> ids, EntityStatus status);
}
