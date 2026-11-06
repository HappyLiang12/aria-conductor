package io.aria.conductor.common.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent_sessions", indexes = {
        @Index(name = "idx_sessions_agent", columnList = "agentId"),
        @Index(name = "idx_sessions_status", columnList = "status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentSession {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID runId;

    @Column(nullable = false)
    private UUID agentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SessionStatus status;

    @Column(columnDefinition = "TEXT")
    private String memory;

    @Column(columnDefinition = "TEXT")
    private String context;

    @Builder.Default
    private int turnCount = 0;

    @Builder.Default
    private long totalInputTokens = 0;

    @Builder.Default
    private long totalOutputTokens = 0;

    @Version
    private int version;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = SessionStatus.ACTIVE;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
