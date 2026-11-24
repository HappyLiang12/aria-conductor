package io.aria.conductor.common.repository;

import io.aria.conductor.common.model.ToolDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ToolDefinitionRepository extends JpaRepository<ToolDefinition, String> {

    Optional<ToolDefinition> findByName(String name);
}
