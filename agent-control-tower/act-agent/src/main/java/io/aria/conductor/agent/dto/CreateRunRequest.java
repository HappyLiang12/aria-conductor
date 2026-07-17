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
}
