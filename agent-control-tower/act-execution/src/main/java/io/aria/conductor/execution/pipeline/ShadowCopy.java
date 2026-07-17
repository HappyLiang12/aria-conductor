package io.aria.conductor.execution.pipeline;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Pre-execution state snapshot captured by Stage 5 of the action pipeline for
 * reversible actions. Stored for audit / manual recovery only — there is NO
 * automatic rollback.
 */
@Entity
@Table(name = "action_shadow_copies", indexes = {
        @Index(name = "idx_shadow_run", columnList = "run_id"),
        @Index(name = "idx_shadow_action", columnList = "action_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShadowCopy {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "run_id", nullable = false, length = 36)
    private String runId;

    @Column(name = "action_id", nullable = false, length = 36)
    private String actionId;

    @Column(name = "action_type", length = 50)
    private String actionType;

    @Column(name = "original_state", columnDefinition = "TEXT")
    private String originalState;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
