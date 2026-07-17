package io.aria.conductor.execution.dod.dto;

import jakarta.validation.constraints.NotBlank;

public record SubmitReviewRequest(
        @NotBlank String taskId,
        @NotBlank String reviewerId,
        String reviewerName,
        boolean passed,
        String evidence,
        String comment
) {}
