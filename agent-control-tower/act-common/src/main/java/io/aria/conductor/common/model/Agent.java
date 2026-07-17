package io.aria.conductor.common.model;

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
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
