package io.aria.conductor.common.repository;

import io.aria.conductor.common.model.RoleSkillTemplate;
import io.aria.conductor.common.model.RoleSkillTemplateId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface RoleSkillTemplateRepository extends JpaRepository<RoleSkillTemplate, RoleSkillTemplateId> {
    @Query("SELECT rt.id.skillId FROM RoleSkillTemplate rt WHERE rt.id.role = :role AND rt.isDefault = true")
    List<String> findDefaultSkillIdsByRole(@Param("role") String role);
}
