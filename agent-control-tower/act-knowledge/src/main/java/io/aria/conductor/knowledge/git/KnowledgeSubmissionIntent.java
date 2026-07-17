package io.aria.conductor.knowledge.git;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable saga record for processing a knowledge item submission through Git.
 * One row tracks one submission attempt. The saga worker advances state
 * machine: QUEUED → BRANCH_CREATED → COMMITTED → MERGED → COMPLETE | FAILED.
 */
@Entity
@Table(name = "knowledge_submission_intents", indexes = {
        @Index(name = "idx_intent_status", columnList = "status"),
        @Index(name = "idx_intent_item", columnList = "item_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeSubmissionIntent {

    public enum Status {
        QUEUED, BRANCH_CREATED, COMMITTED, MERGED, COMPLETE, FAILED
    }

    @Id
    @Column(columnDefinition = "VARCHAR(36)")
    private String id;

    @Column(name = "item_id", nullable = false, length = 36)
    private String itemId;

    @Column(name = "repo_name", nullable = false, length = 100)
    private String repoName;

    @Column(name = "branch_name", length = 64)
    private String branchName;

    @Column(name = "file_path", length = 255)
    private String filePath;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "retry_count")
    private Integer retryCount;

    @Column(name = "max_retries")
    private Integer maxRetries;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = Status.QUEUED;
        if (retryCount == null) retryCount = 0;
        if (maxRetries == null) maxRetries = 5;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
