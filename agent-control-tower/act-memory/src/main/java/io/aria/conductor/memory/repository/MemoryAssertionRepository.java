package io.aria.conductor.memory.repository;

import io.aria.conductor.memory.model.MemoryAssertion;
import io.aria.conductor.memory.model.MemorySubjectType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MemoryAssertionRepository extends JpaRepository<MemoryAssertion, UUID> {

    /** Entity detail: the audit trail of one subject, oldest first. */
    List<MemoryAssertion> findBySubjectTypeAndSubjectIdOrderByCreatedAtAsc(
            MemorySubjectType subjectType, UUID subjectId);
}
