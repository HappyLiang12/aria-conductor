package io.aria.conductor.execution.pipeline;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Persistence access for {@link ShadowCopy} pre-execution snapshots.
 */
@Repository
public interface ShadowCopyRepository extends JpaRepository<ShadowCopy, String> {

    List<ShadowCopy> findByRunIdOrderByCreatedAtAsc(String runId);

    Optional<ShadowCopy> findFirstByRunIdAndActionIdOrderByCreatedAtDesc(String runId, String actionId);
}
