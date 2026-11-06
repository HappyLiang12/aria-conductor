package io.aria.conductor.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateRunRequest {

    @NotNull(message = "Agent ID is required")
    private UUID agentId;

    @NotBlank(message = "Prompt seed is required")
    private String promptSeed;

    @Builder.Default
    private int maxIterations = 50;

    /**
     * Suppresses the RunKanbanAutoCreator auto-card for runs whose creating
     * flow manages kanban linkage itself (kanban orchestrator pickup).
     */
    @Builder.Default
    private boolean suppressAutoCard = false;

    /**
     * The dispatching turn's run id when this run is a child dispatched by the
     * Aria run tool. Null for every non-dispatched run. Never a conversationId:
     * dispatched children must not enter the conversation timeline/context.
     */
    private UUID dispatchedByRunId;
}
