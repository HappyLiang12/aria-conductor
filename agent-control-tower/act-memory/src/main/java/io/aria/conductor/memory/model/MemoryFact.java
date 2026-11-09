package io.aria.conductor.memory.model;

import io.aria.conductor.common.model.Sensitivity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A time-validated key/value fact about an entity. {@code valid_to} null means the
 * value is current; a supersede closes the old row and opens the new one in the same
 * transaction. One ACTIVE current value per (entity, fact key) is enforced by the
 * service with an optimistic version check — not by a partial unique index, because
 * H2 and MariaDB differ there.
 */
@Entity
@Table(name = "memory_facts",
        uniqueConstraints = @UniqueConstraint(name = "uq_memory_fact_proposal", columnNames = "proposal_hash"),
        indexes = @Index(name = "idx_memory_fact_entity_key", columnList = "entity_id, fact_key, valid_to"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryFact {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "owner_id", columnDefinition = "UUID")
    private UUID ownerId;

    @Column(name = "entity_id", nullable = false, columnDefinition = "UUID")
    private UUID entityId;

    /** e.g. timezone, preferred_editor, default_branch. */
    @Column(name = "fact_key", nullable = false, length = 128)
    private String factKey;

    @Column(name = "value_text", columnDefinition = "TEXT")
    private String valueText;

    /** STRING, NUMBER, BOOLEAN, ENUM. */
    @Column(name = "value_type", nullable = false, length = 16)
    private String valueType;

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

    /** Null means the value is current. */
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
        // Stamped on insert as well as on update: the recency order of a fact is
        // OrderByUpdatedAtDesc, so a freshly inserted row must not sort as unknown.
        if (updatedAt == null) updatedAt = Instant.now();
        if (status == null) status = FactStatus.PENDING;
        if (sensitivity == null) sensitivity = Sensitivity.INTERNAL;
        if (origin == null) origin = MemoryOrigin.EXTRACTION;
        if (valueType == null) valueType = "STRING";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
