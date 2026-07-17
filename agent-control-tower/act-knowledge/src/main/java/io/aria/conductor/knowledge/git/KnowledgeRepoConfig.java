package io.aria.conductor.knowledge.git;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Configuration for one local knowledge repository (one per knowledge type).
 * Tracks last successful sync time so the {@link FilesystemMirror} can detect
 * staleness in O(1) without inspecting Git directly.
 */
@Entity
@Table(name = "knowledge_repos")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeRepoConfig {

    @Id
    @Column(columnDefinition = "VARCHAR(36)")
    private String id;

    @Column(unique = true, nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 20)
    private String type;

    @Column(name = "local_path", length = 500)
    private String localPath;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
