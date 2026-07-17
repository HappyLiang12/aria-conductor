package io.aria.conductor.common.repository;

import io.aria.conductor.common.model.SkillTool;
import io.aria.conductor.common.model.SkillToolId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface SkillToolRepository extends JpaRepository<SkillTool, SkillToolId> {
    @Query("SELECT st.id.toolId FROM SkillTool st WHERE st.id.skillId = :skillId")
    List<String> findToolIdsBySkillId(@Param("skillId") String skillId);
}
