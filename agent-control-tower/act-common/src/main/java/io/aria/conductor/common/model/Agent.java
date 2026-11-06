package io.aria.conductor.common.model;

import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.runtime.WorkspaceMode;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agents", indexes = {
        @Index(name = "idx_agents_type", columnList = "agentType"),
        @Index(name = "idx_agents_health", columnList = "healthStatus")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Agent {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AgentType agentType;

    @Column(columnDefinition = "TEXT")
    private String role;

    private String model;

    private String provider;

    private String adkProvider;

    /**
     * Explicit execution placement ({@code HOST}/{@code SANDBOX}). Nullable so
     * legacy rows keep unknown metadata; migration V59 backfills only the
     * pre-existing OpenCode agents to Sandbox.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private ExecutionMode executionMode;

    /**
     * Host workspace selection ({@code WORKTREE}/{@code DIRECT}); unknown for
     * legacy and sandbox-only rows.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private WorkspaceMode workspaceMode;

    /** Trusted backend-local directory for Host execution. */
    @Column(columnDefinition = "TEXT")
    private String workspacePath;

    /** Optional git base ref used to create the Host worktree. */
    @Column(length = 255)
    private String workspaceBaseRef;

    @Column(nullable = false)
    private Boolean pickupEnabled;

    private Instant lastProbedAt;

    @Column(columnDefinition = "TEXT")
    private String config;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HealthStatus healthStatus;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;

    private Instant retiredAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
        if (healthStatus == null) healthStatus = HealthStatus.HEALTHY;
        if (pickupEnabled == null) pickupEnabled = Boolean.TRUE;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
