package io.aria.conductor.memory.model;

import io.aria.conductor.common.model.Sensitivity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A typed edge between two entities (WORKS_ON, OWNS, DEPENDS_ON, LOCATED_AT).
 * {@code proposal_hash} makes an extraction proposal idempotent: the unique index
 * allows many NULLs (operator/run writes) but only one row per proposal.
 */
@Entity
@Table(name = "memory_relations",
        uniqueConstraints = @UniqueConstraint(name = "uq_memory_relation_proposal", columnNames = "proposal_hash"),
        indexes = {
                @Index(name = "idx_memory_relation_from", columnList = "from_entity_id, predicate"),
                @Index(name = "idx_memory_relation_to", columnList = "to_entity_id, predicate")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryRelation {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "owner_id", columnDefinition = "UUID")
    private UUID ownerId;

    @Column(name = "from_entity_id", nullable = false, columnDefinition = "UUID")
    private UUID fromEntityId;

    /** Open vocabulary: WORKS_ON, OWNS, DEPENDS_ON, LOCATED_AT. */
    @Column(nullable = false, length = 64)
    private String predicate;

    @Column(name = "to_entity_id", nullable = false, columnDefinition = "UUID")
    private UUID toEntityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FactStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MemoryOrigin origin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Sensitivity sensitivity;

    @Column(precision = 3, scale = 2)
    private BigDecimal confidence;

    /** Idempotency key of the extraction proposal that created this row. */
    @Column(name = "proposal_hash", length = 64)
    private String proposalHash;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    /** Null means the relation is current. */
    @Column(name = "valid_to")
    private Instant validTo;

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
        if (validFrom == null) validFrom = Instant.now();
        // Stamped on insert as well as on update, for consistency with entities and facts.
        if (updatedAt == null) updatedAt = Instant.now();
        if (status == null) status = FactStatus.PENDING;
        if (sensitivity == null) sensitivity = Sensitivity.INTERNAL;
        if (origin == null) origin = MemoryOrigin.EXTRACTION;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
