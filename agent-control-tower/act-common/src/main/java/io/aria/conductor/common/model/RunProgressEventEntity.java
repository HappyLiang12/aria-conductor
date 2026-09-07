package io.aria.conductor.common.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted run progress fragment (thinking/tool/iteration stream) — replaces the
 * S8 "transient, never persisted" contract (reversed by the 2026-09-06 UI audit:
 * the Live Activity Stream needs history replay).
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

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }
}
