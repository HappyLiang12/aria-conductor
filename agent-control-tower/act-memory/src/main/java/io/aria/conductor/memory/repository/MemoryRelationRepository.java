package io.aria.conductor.memory.repository;

import io.aria.conductor.memory.model.MemoryRelation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface MemoryRelationRepository extends JpaRepository<MemoryRelation, UUID> {
}
