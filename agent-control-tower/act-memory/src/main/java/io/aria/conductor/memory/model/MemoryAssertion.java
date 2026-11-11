package io.aria.conductor.memory.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only audit row: what changed, who changed it, and the before/after payload.
 * Rows are never updated or deleted except by an audited purge.
 */
@Entity
@Table(name = "memory_assertions",
        indexes = @Index(name = "idx_memory_assertion_subject",
                columnList = "subject_type, subject_id, created_at"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryAssertion {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 16)
    private MemorySubjectType subjectType;

    @Column(name = "subject_id", nullable = false, columnDefinition = "UUID")
    private UUID subjectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MemoryAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 16)
    private MemoryActorType actorType;

    /** Run id, conversation id, or operator reference. */
    @Column(name = "actor_ref")
    private String actorRef;

    /** Audit payload; never secrets. */
    @Column(name = "before_json", columnDefinition = "TEXT")
    private String beforeJson;

    @Column(name = "after_json", columnDefinition = "TEXT")
    private String afterJson;

    /** Provenance pointer (message, extraction job, or tool call). */
    @Column(name = "source_ref")
    private String sourceRef;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }
}
