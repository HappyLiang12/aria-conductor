package io.aria.conductor.execution.repository;

import io.aria.conductor.common.model.ToolCall;
import io.aria.conductor.common.model.ToolCallStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ToolCallRepository extends JpaRepository<ToolCall, UUID> {
    List<ToolCall> findByRunId(UUID runId);
    List<ToolCall> findByRunIdAndStatus(UUID runId, ToolCallStatus status);
    void deleteByRunIdIn(List<UUID> runIds);
}