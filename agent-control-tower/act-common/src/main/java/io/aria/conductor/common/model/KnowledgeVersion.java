package io.aria.conductor.common.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "knowledge_versions", indexes = {
        @Index(name = "idx_kv_item", columnList = "knowledgeItemId")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeVersion {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(nullable = false)
    private UUID knowledgeItemId;

    @Column(nullable = false)
    private String version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VersionStatus status;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "TEXT")
    private String yamlContent;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant approvedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = VersionStatus.PENDING;
    }
}
