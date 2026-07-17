package io.aria.conductor.agent.repository;

import io.aria.conductor.common.model.WorkflowChain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WorkflowChainRepository extends JpaRepository<WorkflowChain, UUID> {
    List<WorkflowChain> findByStatus(WorkflowChain.Status status);
    List<WorkflowChain> findByIsTemplateTrue();
    List<WorkflowChain> findByKnowledgeItemId(UUID knowledgeItemId);
    List<WorkflowChain> findByNameContainingIgnoreCase(String name);
}
