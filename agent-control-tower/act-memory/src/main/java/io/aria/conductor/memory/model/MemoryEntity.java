package io.aria.conductor.memory.model;

import io.aria.conductor.common.model.Sensitivity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A durable thing the operator and the agent share: a person, project, repository,
 * machine, agent, task, or org. The canonical key is the deterministic recall handle;
 * {@code attributes} holds JSON display metadata that is not time-validated.
 * <p>
 * Facts and relations reference entities by id only — there is no cross-module (or
 * even intra-module) foreign key, so a merge re-points rows in one transaction.
 */
@Entity
@Table(name = "memory_entities",
        uniqueConstraints = @UniqueConstraint(name = "uq_memory_entity", columnNames = {"kind", "canonical_key"}),
        indexes = @Index(name = "idx_memory_entity_kind_status", columnList = "kind, status"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryEntity {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    /** Reserved for a later multi-operator scope; not enforced in M1. */
    @Column(name = "owner_id", columnDefinition = "UUID")
    private UUID ownerId;

    /** Open vocabulary: PERSON, PROJECT, REPO, MACHINE, AGENT, TASK, ORG. */
    @Column(nullable = false, length = 32)
    private String kind;

    @Column(name = "canonical_key", nullable = false)
    private String canonicalKey;

    @Column(name = "display_name")
    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String attributes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Sensitivity sensitivity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EntityStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MemoryOrigin origin;

    /** Redirect target after an operator merge (status MERGED). */
    @Column(name = "merged_into", columnDefinition = "UUID")
    private UUID mergedInto;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
        // Stamped on insert as well as on update, so "newest first" is defined for a row
        // that has never been updated.
        if (updatedAt == null) updatedAt = Instant.now();
        if (status == null) status = EntityStatus.ACTIVE;
        if (sensitivity == null) sensitivity = Sensitivity.INTERNAL;
        if (origin == null) origin = MemoryOrigin.EXTRACTION;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
