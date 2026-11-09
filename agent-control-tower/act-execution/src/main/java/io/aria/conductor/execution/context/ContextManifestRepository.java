package io.aria.conductor.execution.context;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface ContextManifestRepository extends JpaRepository<ContextManifest, UUID> {

    /**
     * Every assembly recorded for one run, in turn order — the read behind
     * {@code GET /api/v1/memory/runs/{id}/context-manifest}.
     */
    List<ContextManifest> findByRunIdOrderByTurnNumberAsc(UUID runId);

    /**
     * Housekeeping purge of the manifests of the named runs. Derived delete: callers pass
     * the batch they are already deleting; it is transactional so the purge commits or
     * rolls back with the rest of the run teardown.
     */
    @Transactional
    void deleteByRunIdIn(Collection<UUID> runIds);
}
