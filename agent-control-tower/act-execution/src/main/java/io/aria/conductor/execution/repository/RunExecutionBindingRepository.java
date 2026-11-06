package io.aria.conductor.execution.repository;

import io.aria.conductor.common.model.RunExecutionBinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Run-keyed store of immutable execution bindings. The run UUID is the entity
 * key: one binding per run, written before launch and read by the running task
 * instead of re-resolving the agent's mutable settings.
 *
 * <p>The binding row is a run-scoped child (the schema has no cascade from
 * {@code runs}), so a purged run's binding is deleted with the other children,
 * before the run row itself.
 */
@Repository
public interface RunExecutionBindingRepository extends JpaRepository<RunExecutionBinding, UUID> {

    /** Bulk-deletes the bindings of the given runs (housekeeping chunk); the caller owns the transaction. */
    @Modifying
    @Query("delete from RunExecutionBinding b where b.runId in :ids")
    int deleteByRunIdInBulk(@Param("ids") List<UUID> ids);
}
