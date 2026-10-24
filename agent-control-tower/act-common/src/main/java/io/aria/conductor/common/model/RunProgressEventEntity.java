package io.aria.conductor.common.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted run progress fragment (thinking/tool/answer/iteration stream) — replaces
 * the S8 "transient, never persisted" contract (reversed by the 2026-09-06 UI audit:
 * the Live Activity Stream needs history replay).
 *
 * <p>One row is an <strong>observation of one part</strong> (issue #103 growth fix):
 * {@code partId} is the native part's stable identity and {@code seq} the monotonic
 * order of observations, so a part that grew between two polls is stored as a second
 * row with the SAME {@code partId} and a greater {@code seq}. Replay keeps every
 * observation ordered by {@code seq} and the client merges them into one line; the
 * row is deliberately not upserted, so the persisted backlog is an append-only audit
 * of what the operator watched live. {@code partId} is NULL for rows written before
 * V74 and for a core that reports no part id — there {@code seq} is the identity.
 */
@Entity
@Table(name = "run_progress_events", indexes = {
        @Index(name = "idx_progress_run_seq", columnList = "run_id, seq"),
        @Index(name = "idx_progress_created", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunProgressEventEntity {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(nullable = false, columnDefinition = "UUID")
    private UUID runId;

    @Column(columnDefinition = "UUID")
    private UUID agentId;

    @Column(nullable = false)
    private int iteration;

    @Column(nullable = false, length = 32)
    private String kind;

    @Column(nullable = false)
    private long seq;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(length = 255)
    private String toolName;

    /**
     * Stable identity of the observed part (nullable; added by V74). A part is the
     * thing the Live Activity Stream shows, so successive observations of one growing
     * part share this value and differ only by {@code seq}.
     */
    @Column(name = "part_id", length = 128)
    private String partId;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }
}
