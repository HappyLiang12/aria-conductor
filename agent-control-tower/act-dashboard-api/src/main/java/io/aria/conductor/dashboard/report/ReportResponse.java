package io.aria.conductor.dashboard.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Wire-format report metadata returned by {@link ReportController}. Mirrors
 * {@link ReportArtifact} but adds {@link #htmlUrl} so the frontend can render
 * the iframe without reconstructing the URL itself.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportResponse {

    private String id;
    private String title;
    private String sourceRunId;
    private String owner;
    private String sensitivity;
    private String dataScope;
    private String htmlPath;
    private String htmlUrl;
    private Integer version;
    private String status;
    private Instant createdAt;
    private Instant amendedAt;
    private String amendmentHistory;

    public static ReportResponse from(ReportArtifact artifact) {
        if (artifact == null) return null;
        return ReportResponse.builder()
                .id(artifact.getId())
                .title(artifact.getTitle())
                .sourceRunId(artifact.getSourceRunId())
                .owner(artifact.getOwner())
                .sensitivity(artifact.getSensitivity())
                .dataScope(artifact.getDataScope())
                .htmlPath(artifact.getHtmlPath())
                .htmlUrl("/api/v1/reports/" + artifact.getId() + "/html")
                .version(artifact.getVersion())
                .status(artifact.getStatus())
                .createdAt(artifact.getCreatedAt())
                .amendedAt(artifact.getAmendedAt())
                .amendmentHistory(artifact.getAmendmentHistory())
                .build();
    }
}
