package io.aria.conductor.execution.repository;

import io.aria.conductor.common.model.PromptCall;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface PromptCallRepository extends JpaRepository<PromptCall, Long> {
    List<PromptCall> findByRunId(UUID runId);
    List<PromptCall> findByAgentId(UUID agentId);

    /** Housekeeping S1: single-statement bulk delete (set-based, no entity load). */
    @Modifying
    @Query("DELETE FROM PromptCall p WHERE p.runId IN :ids")
    int deleteByRunIdInBulk(@Param("ids") List<UUID> ids);
    long countByAgentId(UUID agentId);

    @Query("SELECT p.agentId, SUM(p.inputTokens + p.outputTokens), COUNT(p) " +
           "FROM PromptCall p WHERE p.createdAt >= :since " +
           "GROUP BY p.agentId")
    List<Object[]> aggregateByAgentSince(@Param("since") Instant since);
}