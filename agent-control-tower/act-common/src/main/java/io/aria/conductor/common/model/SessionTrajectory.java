package io.aria.conductor.common.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "session_trajectory", indexes = {
        @Index(name = "idx_trajectory_run", columnList = "runId"),
        @Index(name = "idx_trajectory_turn", columnList = "runId, turnNumber")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionTrajectory {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(nullable = false)
    private UUID runId;

    @Column(nullable = false)
    private int turnNumber;

    @Column(nullable = false)
    private String role;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(columnDefinition = "TEXT")
    private String toolCalls;

    @Column(columnDefinition = "VARCHAR(255)")
    private String toolCallId;

    private int inputTokens;

    private int outputTokens;

    private int latencyMs;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }
}
