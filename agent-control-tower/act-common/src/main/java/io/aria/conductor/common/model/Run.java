package io.aria.conductor.common.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "runs", indexes = {
        @Index(name = "idx_runs_agent", columnList = "agentId"),
        @Index(name = "idx_runs_status", columnList = "status"),
        @Index(name = "idx_runs_conversation", columnList = "conversationId")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Run {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(nullable = false)
    private UUID agentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status;

    @Column(columnDefinition = "TEXT")
    private String promptSeed;

    @Column(name = "conversation_id", length = 36)
    private String conversationId;

    @Builder.Default
    private int maxIterations = 50;

    @Builder.Default
    private long totalTokensUsed = 0;

    @Builder.Default
    private int iterationCount = 0;

    private String errorMessage;

    @Column(columnDefinition = "TEXT")
    private String finalOutput;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;

    private Instant completedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = RunStatus.PENDING;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
