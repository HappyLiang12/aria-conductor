package io.aria.conductor.knowledge.dto;

import io.aria.conductor.common.model.Sensitivity;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillCreateRequest {

    /** Display name of the registry entry (unique, best-effort). */
    @NotBlank(message = "name is required")
    private String name;

    /** Executable prompt template (Mustache-style {{variable}} placeholders allowed). */
    @NotBlank(message = "template is required")
    private String template;

    private String description;

    /** Free text or JSON object (e.g. {"variables":["..."]}). */
    private String triggerConditions;

    /** JSON array of example inputs/outputs. */
    private String examples;

    /** Defaults to TIER_2 (matches the V21 skill_definitions column default). */
    private String tier;

    /** Defaults to INTERNAL. */
    private Sensitivity sensitivity;
}
