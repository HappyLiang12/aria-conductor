package io.aria.conductor.memory.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Links a knowledge item to a memory entity without moving the document out of
 * act-knowledge. {@code knowledgeItemId} is a logical reference — there is no
 * cross-module foreign key.
 */
@Entity
@Table(name = "memory_knowledge_links",
        uniqueConstraints = @UniqueConstraint(name = "uq_memory_knowledge_link",
                columnNames = {"entity_id", "knowledge_item_id"}),
        indexes = @Index(name = "idx_memory_knowledge_link_knowledge", columnList = "knowledge_item_id"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryKnowledgeLink {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "entity_id", nullable = false, columnDefinition = "UUID")
    private UUID entityId;

    @Column(name = "knowledge_item_id", nullable = false, columnDefinition = "UUID")
    private UUID knowledgeItemId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MemoryLinkStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by")
    private String decidedBy;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = MemoryLinkStatus.PROPOSED;
    }
}
