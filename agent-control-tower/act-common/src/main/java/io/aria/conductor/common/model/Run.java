package io.aria.conductor.common.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A business run record. Run-level execution metadata (resolved core, mode,
 * workspace, credential reference, deadline, runtime identity, usage) is
 * deliberately NOT duplicated here: it lives in the immutable, run-keyed
 * {@link RunExecutionBinding} recorded before launch, so the running task never
 * re-resolves the agent's mutable settings. Legacy runs have no binding and
 * receive no invented historical core snapshot; unknown usage stays unknown and
 * must not be reported as zero.
 */
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

    /**
     * The Aria turn's run that dispatched this run, when this run is a child of a
     * dispatch group. Dispatched children deliberately never carry
     * {@link #conversationId}: the timeline/context select by it, so stamping it
     * here would pollute the Aria chat. NULL for every legacy and non-child run.
     */
    @Column(name = "dispatched_by_run_id", columnDefinition = "UUID")
    private UUID dispatchedByRunId;

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
