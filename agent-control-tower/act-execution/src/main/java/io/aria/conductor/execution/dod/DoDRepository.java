package io.aria.conductor.execution.dod;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DoDRepository extends JpaRepository<DoDRecord, String> {
    Optional<DoDRecord> findByTaskId(String taskId);
}
