package io.aria.conductor.execution.repository;

import io.aria.conductor.common.model.ToolCall;
import io.aria.conductor.common.model.ToolCallStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ToolCallRepository extends JpaRepository<ToolCall, UUID> {
    List<ToolCall> findByRunId(UUID runId);
    List<ToolCall> findByRunIdAndStatus(UUID runId, ToolCallStatus status);
    void deleteByRunIdIn(List<UUID> runIds);

    /** Housekeeping S1: single-statement bulk delete (set-based, no entity load). */
    @Modifying
    @Query("DELETE FROM ToolCall t WHERE t.runId IN :ids")
    int deleteByRunIdInBulk(@Param("ids") List<UUID> ids);
}