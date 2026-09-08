package io.aria.conductor.execution.kanban;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransitionRequest {

    @NotNull(message = "Target status is required")
    private KanbanStatus status;

    private String comment;

    /** Used when a REVIEW card is sent back to TODO (request changes). */
    private String feedback;

    /** Optional agent template hint for pickup; overrides item value. */
    private String agentTemplateId;
}
