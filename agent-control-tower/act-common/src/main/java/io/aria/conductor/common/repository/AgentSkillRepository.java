package io.aria.conductor.common.repository;

import io.aria.conductor.common.model.AgentSkill;
import io.aria.conductor.common.model.AgentSkillId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface AgentSkillRepository extends JpaRepository<AgentSkill, AgentSkillId> {
    @Query("SELECT aks.id.skillId FROM AgentSkill aks WHERE aks.id.agentId = :agentId")
    List<String> findSkillIdsByAgentId(@Param("agentId") String agentId);
}
