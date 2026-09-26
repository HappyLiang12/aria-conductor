package io.aria.conductor.agent.repository;

import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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

    /** Housekeeping S1: single-statement bulk delete of terminal runs (children purged first). */
    @Modifying
    @Query("DELETE FROM Run r WHERE r.id IN :ids")
    int deleteByIdInBulk(@Param("ids") List<UUID> ids);
}
