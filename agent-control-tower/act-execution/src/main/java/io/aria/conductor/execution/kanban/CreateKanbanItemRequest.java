package io.aria.conductor.execution.kanban;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateKanbanItemRequest {

    @NotBlank(message = "Title is required")
    private String title;

    private String description;

    /** Initial status; defaults to TODO when omitted (e.g. BACKLOG from the new-task modal). */
    private KanbanStatus status;

    private KanbanPriority priority;

    private String assignee;

    private String labels;

    private String linkedRunId;

    private String linkedAgentId;

    /** Agent template the new-task modal assigns; null lets Aria auto-assign. */
    private String agentTemplateId;
}
