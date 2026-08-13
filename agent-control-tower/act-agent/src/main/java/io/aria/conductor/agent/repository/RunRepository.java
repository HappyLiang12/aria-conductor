package io.aria.conductor.agent.repository;

import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RunRepository extends JpaRepository<Run, UUID> {
    List<Run> findByAgentId(UUID agentId);
    List<Run> findByStatus(RunStatus status);
    List<Run> findByStatusIn(List<RunStatus> statuses);
    List<Run> findByAgentIdAndStatus(UUID agentId, RunStatus status);
    long countByStatus(RunStatus status);
    List<Run> findByConversationIdOrderByCreatedAtAsc(String conversationId);
}
