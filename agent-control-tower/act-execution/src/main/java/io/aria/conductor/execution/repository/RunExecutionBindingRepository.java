package io.aria.conductor.execution.repository;

import io.aria.conductor.common.model.RunExecutionBinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Run-keyed store of immutable execution bindings. The run UUID is the entity
 * key: one binding per run, written before launch and read by the running task
 * instead of re-resolving the agent's mutable settings.
 */
@Repository
public interface RunExecutionBindingRepository extends JpaRepository<RunExecutionBinding, UUID> {
}
