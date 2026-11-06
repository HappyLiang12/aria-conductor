package io.aria.conductor.execution.dod.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateEvidenceRequest(
        @NotBlank String type,
        String title,
        String content,
        String artifactPath,
        String sourceRunId
) {}
