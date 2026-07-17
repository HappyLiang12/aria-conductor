package io.aria.conductor.execution.dod;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Single stage review submitted against a {@link DoDRecord}.
 * One row per (dodId, stage, reviewer) — multiple rows per stage are allowed
 * (e.g. a failure followed by a re-review pass).
 */
@Entity
@Table(name = "dod_stage_reviews")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DoDStageReview {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "dod_id", nullable = false, length = 36)
    private String dodId;

    @Column(nullable = false, length = 20)
    private String stage;

    @Column(name = "reviewer_id", nullable = false, length = 36)
    private String reviewerId;

    @Column(name = "reviewer_name", length = 100)
    private String reviewerName;

    @Column(nullable = false)
    private boolean passed;

    @Column(columnDefinition = "TEXT")
    private String evidence;

    @Column(length = 1000)
    private String comment;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @PrePersist
    protected void onCreate() {
        if (reviewedAt == null) reviewedAt = Instant.now();
    }
}
