package io.aria.conductor.agent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AssignToolRequest {
    @NotBlank
    private String toolId;
}
