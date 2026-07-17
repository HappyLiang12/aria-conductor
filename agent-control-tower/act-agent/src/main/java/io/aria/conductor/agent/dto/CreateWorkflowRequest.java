package io.aria.conductor.agent.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.*;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateWorkflowRequest {

    @NotBlank(message = "Workflow name is required")
    private String name;

    private String description;

    @NotEmpty(message = "At least one step is required")
    @Valid
    private List<StepDef> steps;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StepDef {
        private UUID agentId;
        @NotBlank(message = "Prompt template is required for each step")
        private String promptTemplate;
        @Builder.Default
        private int maxIterations = 3;
    }
}
