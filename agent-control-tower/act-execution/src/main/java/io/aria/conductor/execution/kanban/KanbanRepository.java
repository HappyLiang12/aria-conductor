package io.aria.conductor.execution.kanban;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KanbanRepository extends JpaRepository<KanbanItem, String> {

    List<KanbanItem> findByStatus(KanbanStatus status);

    List<KanbanItem> findByAssignee(String assignee);

    List<KanbanItem> findByLinkedRunId(String runId);
}
