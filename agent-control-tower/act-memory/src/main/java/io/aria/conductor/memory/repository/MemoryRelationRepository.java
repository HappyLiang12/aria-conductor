package io.aria.conductor.memory.repository;

import io.aria.conductor.memory.model.FactStatus;
import io.aria.conductor.memory.model.MemoryRelation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MemoryRelationRepository extends JpaRepository<MemoryRelation, UUID> {

    /**
     * Entity detail / one-hop recall: the edges leaving an entity in one lifecycle status.
     * Geometry only — validity filtering belongs to the caller.
     */
    List<MemoryRelation> findByFromEntityIdAndStatus(UUID fromEntityId, FactStatus status);

    /** The mirrored read: the edges arriving at an entity in one lifecycle status. */
    List<MemoryRelation> findByToEntityIdAndStatus(UUID toEntityId, FactStatus status);

    /**
     * Proposal review inbox: one bounded page of relations in one status, newest first.
     */
    List<MemoryRelation> findByStatusOrderByCreatedAtDesc(FactStatus status, Pageable page);
}
