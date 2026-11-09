package io.aria.conductor.memory.repository;

import io.aria.conductor.memory.model.FactStatus;
import io.aria.conductor.memory.model.MemoryFact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface MemoryFactRepository extends JpaRepository<MemoryFact, UUID> {

    /**
     * Current values of one status: rows with a null {@code valid_to} (a superseded or
     * retired fact keeps its closed row), newest first.
     */
    List<MemoryFact> findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(UUID entityId, FactStatus status);

    /**
     * The current ACTIVE values of an entity. This is the only status a prompt may see;
     * PENDING proposals are operator-review material, not memory.
     */
    default List<MemoryFact> findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(UUID entityId) {
        return findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(entityId, FactStatus.ACTIVE);
    }

    /**
     * Recall fact load: the current values of the matched entities that are already valid
     * at the request instant. A fact whose {@code valid_from} lies in the future is not yet
     * memory, and a closed ({@code valid_to} non-null) row is history.
     */
    List<MemoryFact> findByEntityIdInAndStatusAndValidFromLessThanEqualAndValidToIsNull(
            Collection<UUID> entityIds, FactStatus status, Instant at);

    /**
     * Entity detail: the whole value history of one entity, newest {@code valid_from}
     * first, regardless of status or closure.
     */
    List<MemoryFact> findByEntityIdOrderByValidFromDesc(UUID entityId);
}
