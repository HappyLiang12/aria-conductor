package io.aria.conductor.agent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AssignSkillRequest {
    @NotBlank
    private String skillId;
}
