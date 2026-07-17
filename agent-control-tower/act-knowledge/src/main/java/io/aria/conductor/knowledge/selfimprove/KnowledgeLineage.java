package io.aria.conductor.knowledge.selfimprove;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Lineage edge connecting an ancestor knowledge artifact to a descendant
 * promoted from it. Used to trace provenance across stages of the maturity
 * ladder (e.g., PromptCall cluster → ReusablePrompt → Skill → Script).
 */
@Entity
@Table(name = "knowledge_lineage")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeLineage {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "ancestor_id", nullable = false, length = 36)
    private String ancestorId;

    @Column(name = "descendant_id", nullable = false, length = 36)
    private String descendantId;

    @Column
    private Integer depth;

    /** PROMOTED_FROM | DERIVED_FROM | COMPOSED_OF — provenance kind. */
    @Column(name = "relation_type", length = 50)
    private String relationType;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (depth == null) depth = 1;
    }
}
