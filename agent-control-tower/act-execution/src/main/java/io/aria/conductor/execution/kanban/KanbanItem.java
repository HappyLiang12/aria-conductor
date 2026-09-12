package io.aria.conductor.execution.kanban;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Kanban board item.
 *
 * <p>Backed by table {@code kanban_items} (Flyway V7).
 */
@Entity
@Table(name = "kanban_items", indexes = {
        @Index(name = "idx_kanban_status", columnList = "status"),
        @Index(name = "idx_kanban_assignee", columnList = "assignee")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KanbanItem {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private KanbanStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private KanbanPriority priority;

    @Column(length = 100)
    private String assignee;

    /** Comma-separated label list. */
    @Column(length = 500)
    private String labels;

    @Column(name = "linked_run_id", length = 36)
    private String linkedRunId;

    @Column(name = "linked_agent_id", length = 36)
    private String linkedAgentId;

    /** Agent template requested at creation (assignment hint for pickup). */
    @Column(name = "agent_template_id", length = 100)
    private String agentTemplateId;

    /** Last pickup/transition failure, surfaced on the card face. */
    @Column(name = "last_error", length = 500)
    private String lastError;

    /**
     * Optimistic-lock version (Flyway V54): concurrent board moves of the same
     * card fail the losing writer, mapped to HTTP 409 by the global handler.
     */
    @jakarta.persistence.Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** Not persisted: number of PENDING asks surfaced in the Review column. */
    @Transient
    private Integer pendingAskCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = KanbanStatus.TODO;
        if (priority == null) priority = KanbanPriority.MEDIUM;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
