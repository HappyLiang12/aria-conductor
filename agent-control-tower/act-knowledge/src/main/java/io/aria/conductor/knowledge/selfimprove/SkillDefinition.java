package io.aria.conductor.knowledge.selfimprove;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Reusable skill definition produced by Stage 3 (and beyond) of the
 * self-improvement pipeline. A skill is an executable prompt template with
 * declared trigger conditions and example I/O. Higher stages (SCRIPT,
 * WORKFLOW) reuse the same row by mutating {@code stage}.
 */
@Entity
@Table(name = "skill_definitions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillDefinition {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(columnDefinition = "TEXT")
    private String template;

    /** JSON object describing when this skill applies. */
    @Column(name = "trigger_conditions", columnDefinition = "TEXT")
    private String triggerConditions;

    /** JSON array of example inputs/outputs. */
    @Column(columnDefinition = "TEXT")
    private String examples;

    /** Comma-separated PromptCall ids that fed this skill (provenance). */
    @Column(name = "source_prompt_ids", length = 1000)
    private String sourcePromptIds;

    /** Backlink to the {@code KnowledgeItem} carrying review state. */
    @Column(name = "knowledge_item_id", length = 36)
    private String knowledgeItemId;

    @Column(name = "usage_count")
    private int usageCount;

    /** SKILL | SCRIPT | WORKFLOW — stage in the maturity ladder. */
    @Column(length = 20)
    private String stage;

    /** Whether this skill is eligible for agent prompt injection. */
    @Column(nullable = false)
    private boolean enabled;

    @Column(length = 20)
    private String tier;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (stage == null) stage = "SKILL";
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
