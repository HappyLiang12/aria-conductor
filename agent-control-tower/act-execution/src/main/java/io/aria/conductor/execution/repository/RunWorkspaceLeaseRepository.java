package io.aria.conductor.execution.repository;

import io.aria.conductor.common.model.RunWorkspaceLease;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Store of run workspace leases. Consumers: lease acquisition loads unresolved
 * ({@code ACTIVE}) leases so a fresh process still refuses an overlapping
 * Direct writer after a restart, and ownership-aware cleanup resolves a
 * run-owned directory back to its leases (by run id, the directory name the
 * platform workspace root uses) before removing anything.
 */
@Repository
public interface RunWorkspaceLeaseRepository extends JpaRepository<RunWorkspaceLease, UUID> {

    List<RunWorkspaceLease> findByRunId(UUID runId);

    List<RunWorkspaceLease> findByState(RunWorkspaceLease.State state);

    Optional<RunWorkspaceLease> findFirstByRunIdOrderByAcquiredAtDesc(UUID runId);
}
