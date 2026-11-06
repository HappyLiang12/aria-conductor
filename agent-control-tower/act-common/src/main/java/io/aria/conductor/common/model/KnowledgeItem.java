package io.aria.conductor.common.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "knowledge_items", indexes = {
        @Index(name = "idx_knowledge_type", columnList = "type"),
        @Index(name = "idx_knowledge_status", columnList = "status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeItem {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private KnowledgeType type;

    private String description;

    private String currentVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private KnowledgeStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Sensitivity sensitivity;

    private String filePath;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;

    private Instant retiredAt;

    private Instant reviewDeadline;

    @Column(nullable = false)
    private Integer escalationCount;

    private String reviewerId;

    private String reviewerName;

    private String rejectionReason;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = KnowledgeStatus.DRAFT;
        if (sensitivity == null) sensitivity = Sensitivity.INTERNAL;
        if (escalationCount == null) escalationCount = 0;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
