package io.aria.conductor.common.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "tool_packs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolPack {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PackKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VersionStatus status;

    @Column(name = "sandbox_mode", nullable = false, length = 20)
    @Builder.Default
    private String sandboxMode = "NONE";

    @Column(columnDefinition = "TEXT")
    private String config;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = VersionStatus.PENDING;
        if (sandboxMode == null) sandboxMode = "NONE";
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
