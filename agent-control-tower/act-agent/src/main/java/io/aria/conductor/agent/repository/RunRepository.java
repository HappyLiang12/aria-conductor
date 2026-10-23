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
     * The one terminal CANCELLED write for a run (#111 fix round 1). The cancelable
     * set is evaluated by the database against the row's committed status, so a run
     * that finalized (COMPLETED/FAILED/CANCELLED) between the caller's read and this
     * update can never be overwritten with CANCELLED -- and a concurrent cancel can
     * never publish a second completion event. {@code completedAt} is set by this
     * single statement; there is no read-then-save path left.
     * <p>
     * The caller passes every status whose recorded transition allows CANCELLED
     * ({@code RunService.cancelableStatuses()}); ABORTED is included deliberately
     * because a task-level abort is still cancelable.
     *
     * @return 1 when this caller won the terminal transition, 0 when the run is
     *         already terminal or missing (nothing was written)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE Run r SET r.status = :cancelled, r.completedAt = :completedAt, r.updatedAt = :updatedAt "
            + "WHERE r.id = :id AND r.status IN :cancelable")
    int cancelIfCancelable(@Param("id") UUID id,
                           @Param("cancelled") RunStatus cancelled,
                           @Param("completedAt") Instant completedAt,
                           @Param("updatedAt") Instant updatedAt,
                           @Param("cancelable") List<RunStatus> cancelable);

    /**
     * The one terminal completion write for a run (#111 fix round 1, I1). The
     * terminal set is evaluated inside the UPDATE, so exactly one writer -- the
     * engine's completion or a cancel -- owns the terminal transition and its
     * {@code RunCompletedEvent}. Null final/error values preserve the existing row
     * value, matching the old conditional entity mutations.
     *
     * @return 1 when this caller won the terminal transition, 0 when the run is
     *         already terminal or missing (nothing was written)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE Run r SET r.status = :status, r.iterationCount = :iterationCount, "
            + "r.totalTokensUsed = :totalTokensUsed, r.completedAt = :completedAt, "
            + "r.finalOutput = COALESCE(:finalOutput, r.finalOutput), "
            + "r.errorMessage = COALESCE(:errorMessage, r.errorMessage), "
            + "r.errorCode = COALESCE(:errorCode, r.errorCode), "
            + "r.errorSurface = COALESCE(:errorSurface, r.errorSurface), "
            + "r.updatedAt = :updatedAt "
            + "WHERE r.id = :id AND r.status NOT IN :terminal")
    int completeIfNotTerminal(@Param("id") UUID id,
                              @Param("status") RunStatus status,
                              @Param("iterationCount") int iterationCount,
                              @Param("totalTokensUsed") long totalTokensUsed,
                              @Param("completedAt") Instant completedAt,
                              @Param("finalOutput") String finalOutput,
                              @Param("errorMessage") String errorMessage,
                              @Param("errorCode") String errorCode,
                              @Param("errorSurface") String errorSurface,
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
