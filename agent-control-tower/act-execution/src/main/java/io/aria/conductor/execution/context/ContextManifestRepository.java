package io.aria.conductor.execution.context;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ContextManifestRepository extends JpaRepository<ContextManifest, UUID> {

    /**
     * Every assembly recorded for one run, in turn order — the read behind
     * {@code GET /api/v1/memory/runs/{id}/context-manifest}.
     */
    List<ContextManifest> findByRunIdOrderByTurnNumberAsc(UUID runId);
}
