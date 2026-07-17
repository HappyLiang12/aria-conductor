package io.aria.conductor.execution.dod.dto;

import jakarta.validation.constraints.NotBlank;

public record InitDoDRequest(
        @NotBlank String taskId,
        String taskType
) {}
