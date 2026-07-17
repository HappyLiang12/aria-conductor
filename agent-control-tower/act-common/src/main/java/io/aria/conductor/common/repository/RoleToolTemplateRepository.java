package io.aria.conductor.common.repository;

import io.aria.conductor.common.model.RoleToolTemplate;
import io.aria.conductor.common.model.RoleToolTemplateId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface RoleToolTemplateRepository extends JpaRepository<RoleToolTemplate, RoleToolTemplateId> {
    @Query("SELECT rt.id.toolId FROM RoleToolTemplate rt WHERE rt.id.role = :role AND rt.isDefault = true")
    List<String> findDefaultToolIdsByRole(@Param("role") String role);
}
