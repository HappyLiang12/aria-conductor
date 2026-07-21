package io.aria.conductor.common.repository;

import io.aria.conductor.common.model.AgentSkill;
import io.aria.conductor.common.model.AgentSkillId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Repository
public interface AgentSkillRepository extends JpaRepository<AgentSkill, AgentSkillId> {
    @Query("SELECT aks.id.skillId FROM AgentSkill aks WHERE aks.id.agentId = :agentId")
    List<String> findSkillIdsByAgentId(@Param("agentId") String agentId);

    @Transactional
    @Modifying
    @Query("DELETE FROM AgentSkill aks WHERE aks.id.agentId = :agentId")
    void deleteByAgentId(@Param("agentId") String agentId);
}
