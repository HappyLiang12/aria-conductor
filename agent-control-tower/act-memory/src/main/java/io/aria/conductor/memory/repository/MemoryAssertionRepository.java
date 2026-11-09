package io.aria.conductor.memory.repository;

import io.aria.conductor.memory.model.MemoryAssertion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface MemoryAssertionRepository extends JpaRepository<MemoryAssertion, UUID> {
}
