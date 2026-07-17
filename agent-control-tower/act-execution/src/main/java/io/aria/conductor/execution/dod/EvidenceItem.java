package io.aria.conductor.execution.dod;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Evidence artifact attached to a DoD record. Examples: log excerpts, file
 * artifacts, test results, screenshots, free-form reviewer comments.
 */
@Entity
@Table(name = "evidence_items")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceItem {

    /** Canonical evidence type values used by the dashboard drawer. */
    public static final String TYPE_LOG = "LOG";
    public static final String TYPE_ARTIFACT = "ARTIFACT";
    public static final String TYPE_TEST_RESULT = "TEST_RESULT";
    public static final String TYPE_SCREENSHOT = "SCREENSHOT";
    public static final String TYPE_COMMENT = "COMMENT";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "dod_id", nullable = false, length = 36)
    private String dodId;

    @Column(name = "task_id", nullable = false, length = 36)
    private String taskId;

    @Column(nullable = false, length = 50)
    private String type;

    @Column(length = 255)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "artifact_path", length = 500)
    private String artifactPath;

    @Column(name = "source_run_id", length = 36)
    private String sourceRunId;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
