package io.aria.conductor.common.repository;

import io.aria.conductor.common.model.AgentTool;
import io.aria.conductor.common.model.AgentToolId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Repository
public interface AgentToolRepository extends JpaRepository<AgentTool, AgentToolId> {
    @Query("SELECT at.id.toolId FROM AgentTool at WHERE at.id.agentId = :agentId")
    List<String> findToolIdsByAgentId(@Param("agentId") String agentId);

    @Transactional
    @Modifying
    @Query("DELETE FROM AgentTool at WHERE at.id.agentId = :agentId")
    void deleteByAgentId(@Param("agentId") String agentId);
}
