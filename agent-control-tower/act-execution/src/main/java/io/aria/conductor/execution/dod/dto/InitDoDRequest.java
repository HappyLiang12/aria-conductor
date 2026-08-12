package io.aria.conductor.execution.dod.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record InitDoDRequest(
        @NotBlank String taskId,
        String taskType,
        List<String> stages
) {}
