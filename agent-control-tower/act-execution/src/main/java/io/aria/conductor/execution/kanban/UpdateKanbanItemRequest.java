package io.aria.conductor.execution.kanban;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateKanbanItemRequest {

    private String title;

    private String description;

    private KanbanPriority priority;

    private String assignee;

    private String labels;
}
