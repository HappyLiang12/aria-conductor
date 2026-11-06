package io.aria.conductor.execution.dod.dto;

import io.aria.conductor.execution.dod.DoDRecord;
import io.aria.conductor.execution.dod.DoDStageReview;

import java.time.Instant;
import java.util.List;

/**
 * Aggregate response for {@code GET /api/v1/dod/{taskId}}.
 *
 * <p>Includes the full DoD record plus the per-stage roll-up so the dashboard
 * drawer can render the stage progression strip without extra round-trips.
 */
public record DoDStatusResponse(
        String id,
        String taskId,
        String taskType,
        String currentStage,
        String overallStatus,
        Instant createdAt,
        Instant updatedAt,
        List<StageStatus> stages,
        List<DoDStageReview> reviews,
        long evidenceCount
) {

    /** Per-stage roll-up: required flag, latest pass/fail, review count. */
    public record StageStatus(
            String stage,
            boolean required,
            String status,   // PENDING | PASSED | FAILED | SKIPPED
            int reviewCount,
            Instant lastReviewedAt
    ) {}

    public static DoDStatusResponse of(DoDRecord record,
                                       List<StageStatus> stages,
                                       List<DoDStageReview> reviews,
                                       long evidenceCount) {
        return new DoDStatusResponse(
                record.getId(),
                record.getTaskId(),
                record.getTaskType(),
                record.getCurrentStage(),
                record.getOverallStatus(),
                record.getCreatedAt(),
                record.getUpdatedAt(),
                stages,
                reviews,
                evidenceCount
        );
    }
}
