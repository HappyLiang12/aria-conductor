package io.aria.conductor.common.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a sequential multi-agent workflow chain.
 * Steps are stored as a JSON CLOB to keep the schema simple.
 */
@Entity
@Table(name = "workflow_chains", indexes = {
        @Index(name = "idx_wf_status", columnList = "status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowChain {

    public enum Status { PENDING, RUNNING, WAITING_APPROVAL, COMPLETED, FAILED, CANCELLED }

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(nullable = false)
    private int currentStepIndex;

    /** JSON array of step definitions (agentId, promptTemplate, maxIterations, runId, status). */
    @Column(name = "steps_json", columnDefinition = "TEXT")
    private String stepsJson;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;

    private Instant completedAt;

    @Builder.Default
    @Column(nullable = false)
    private boolean isTemplate = false;

    @Column(columnDefinition = "TEXT")
    private String templateParams;

    @Column(columnDefinition = "UUID")
    private UUID sourceKnowledgeItemId;

    @Column(columnDefinition = "UUID")
    private UUID knowledgeItemId;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "report_artifact_id", columnDefinition = "UUID")
    private UUID reportArtifactId;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = Status.PENDING;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
