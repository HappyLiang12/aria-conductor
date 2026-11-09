package io.aria.conductor.memory.repository;

import io.aria.conductor.memory.model.MemoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface MemoryEntityRepository extends JpaRepository<MemoryEntity, UUID> {
}
