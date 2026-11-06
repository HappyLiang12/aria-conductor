package io.aria.conductor.execution.dod;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Definition of Done record tracking stage-gate progression for a task.
 * One DoDRecord per task. Stage progression: dev -> qa -> ba -> pm.
 */
@Entity
@Table(name = "dod_records")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DoDRecord {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "task_id", nullable = false, unique = true, length = 36)
    private String taskId;

    @Column(name = "task_type", length = 50)
    private String taskType;

    @Column(name = "current_stage", nullable = false, length = 20)
    private String currentStage;

    @Column(name = "overall_status", nullable = false, length = 20)
    private String overallStatus;

    /** Optional custom stage list (JSON array). Null = use DoDService.DEFAULT_STAGES. */
    @Column(name = "stages_json", columnDefinition = "TEXT")
    private String stagesJson;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (currentStage == null) currentStage = "dev";
        if (overallStatus == null) overallStatus = "IN_PROGRESS";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
