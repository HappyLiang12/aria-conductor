package io.aria.conductor.dashboard.report;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Generative-UI report artifact.
 *
 * <p>Backed by table {@code report_artifacts} (Flyway V9). Each row is the
 * metadata for one logical report; the rendered HTML for every version is
 * written to disk under {@code ./data/reports/{id}/v{version}/index.html}
 * and served via {@code GET /api/v1/reports/{id}/html}.
 */
@Entity
@Table(name = "report_artifacts", indexes = {
        @Index(name = "idx_report_owner", columnList = "owner"),
        @Index(name = "idx_report_source", columnList = "source_run_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportArtifact {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false)
    private String title;

    @Column(name = "source_run_id", length = 36)
    private String sourceRunId;

    @Column(length = 100)
    private String owner;

    @Column(length = 20)
    private String sensitivity;

    @Column(name = "data_scope", length = 500)
    private String dataScope;

    @Column(name = "html_path", length = 500)
    private String htmlPath;

    @Column(nullable = false)
    private Integer version;

    /** GENERATED | AMENDED | ARCHIVED. */
    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "amended_at")
    private Instant amendedAt;

    /** JSON array of amendment events: [{version, instruction, at}]. */
    @Column(name = "amendment_history", columnDefinition = "TEXT")
    private String amendmentHistory;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
        if (version == null) version = 1;
        if (status == null) status = "GENERATED";
        if (sensitivity == null) sensitivity = "internal";
    }
}
