package io.aria.conductor.knowledge.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewDecisionRequest {

    @NotNull(message = "Decision is required")
    private ReviewDecision decision;

    private String reason;

    public enum ReviewDecision {
        APPROVED, REJECTED
    }
}
