package io.aria.conductor.common.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "tool_definitions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolDefinition {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "display_name")
    private String displayName;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 20)
    private String tier; // TIER_1, TIER_2, TIER_3

    @Column(nullable = false, length = 50)
    private String category; // GENERAL, PLATFORM, ADVANCED

    @Column(name = "handler_class", length = 500)
    private String handlerClass;

    @Column(name = "script_type", length = 20)
    private String scriptType; // PYTHON, SHELL

    @Column(columnDefinition = "TEXT")
    private String script;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String parameters; // JSON Schema

    @Column(name = "sandbox_mode", nullable = false, length = 20)
    private String sandboxMode; // NONE, PROCESS, DOCKER

    @Column(name = "sandbox_config", columnDefinition = "TEXT")
    private String sandboxConfig; // JSON

    @Column(name = "timeout_ms", nullable = false)
    private int timeoutMs;

    @Column(name = "knowledge_item_id", length = 36)
    private String knowledgeItemId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "created_by")
    private String createdBy;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
