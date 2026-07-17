package io.aria.conductor.common.repository;

import io.aria.conductor.common.model.ToolDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ToolDefinitionRepository extends JpaRepository<ToolDefinition, String> {

    Optional<ToolDefinition> findByName(String name);

    List<ToolDefinition> findByEnabledTrue();

    List<ToolDefinition> findByTierAndEnabledTrue(String tier);

    List<ToolDefinition> findByCategoryAndEnabledTrue(String category);

    @Query("SELECT t FROM ToolDefinition t WHERE t.enabled = true AND t.knowledgeItemId IN " +
           "(SELECT cast(k.id as string) FROM KnowledgeItem k WHERE k.status = 'APPROVED')")
    List<ToolDefinition> findAllApprovedAndEnabled();
}
