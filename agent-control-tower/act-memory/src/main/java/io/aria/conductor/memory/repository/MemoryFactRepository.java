package io.aria.conductor.memory.repository;

import io.aria.conductor.memory.model.FactStatus;
import io.aria.conductor.memory.model.MemoryFact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
