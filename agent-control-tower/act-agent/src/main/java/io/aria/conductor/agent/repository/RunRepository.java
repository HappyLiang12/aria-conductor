package io.aria.conductor.agent.repository;

import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RunRepository extends JpaRepository<Run, UUID> {
    List<Run> findByAgentId(UUID agentId);
    List<Run> findByStatus(RunStatus status);
    List<Run> findByStatusIn(List<RunStatus> statuses);
    List<Run> findByAgentIdAndStatus(UUID agentId, RunStatus status);
    long countByStatus(RunStatus status);
    List<Run> findByConversationIdOrderByCreatedAtAsc(String conversationId);
    List<Run> findByDispatchedByRunId(UUID dispatchedByRunId);

    /**
     * The run's committed status and output, read straight from the database.
     * Deliberately a projection: {@code findById} serves the persistence-context
     * instance (Open Session In View binds it to the HTTP request), so a watcher
     * polling for another transaction's terminal state would keep seeing its own
     * stale snapshot. This query always issues its SELECT.
     */
    @Query("SELECT r.status AS status, r.finalOutput AS finalOutput FROM Run r WHERE r.id = :id")
    Optional<RunStateView> findStateById(@Param("id") UUID id);

    interface RunStateView {
        RunStatus getStatus();
        String getFinalOutput();
    }

    /**
     * Single-statement status write that refuses to touch a terminal run. A read-then-
     * save would let a cancel commit between the read and the save, and the stale save
     * would resurrect the run; the condition is evaluated by the database atomically.
     * Returns the number of rows updated (0 = the run is terminal or missing).
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE Run r SET r.status = :status, r.updatedAt = :updatedAt "
            + "WHERE r.id = :id AND r.status NOT IN :terminal")
    int updateStatusUnlessTerminal(@Param("id") UUID id,
                                   @Param("status") RunStatus status,
                                   @Param("updatedAt") Instant updatedAt,
                                   @Param("terminal") List<RunStatus> terminal);

    /**
     * Persists the resolved iteration cap only while the run is still startable, so a
     * queued/cancelled run never receives the write.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE Run r SET r.maxIterations = :maxIterations WHERE r.id = :id AND r.status IN :startable")
    int updateMaxIterationsIfStartable(@Param("id") UUID id,
                                       @Param("maxIterations") int maxIterations,
                                       @Param("startable") List<RunStatus> startable);

    /** Housekeeping S1: single-statement bulk delete of terminal runs (children purged first). */
    @Modifying
    @Query("DELETE FROM Run r WHERE r.id IN :ids")
    int deleteByIdInBulk(@Param("ids") List<UUID> ids);
}
