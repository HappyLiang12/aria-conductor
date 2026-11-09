package io.aria.conductor.execution.context;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Provenance of one context assembly: which sections were rendered (ids, source ids,
 * estimates), what was dropped or degraded, and for which provider. The full prompt
 * content is never stored — the manifest is metadata only.
 * <p>
 * One row per turn of a run ({@code uq_context_manifest_turn}); {@code adapter} is the
 * transport that assembled the prompt ({@code SYNC}, {@code STREAM}, {@code CORE}),
 * stored as a string so this entity never depends on the transport SPI's enum.
 */
@Entity
@Table(name = "context_manifests",
        uniqueConstraints = @UniqueConstraint(name = "uq_context_manifest_turn",
                columnNames = {"run_id", "turn_number"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContextManifest {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "run_id", nullable = false, columnDefinition = "UUID")
    private UUID runId;

    @Column(name = "turn_number", nullable = false)
    private int turnNumber;

    @Column(name = "conversation_id")
    private String conversationId;

    @Column(name = "agent_id", columnDefinition = "UUID")
    private UUID agentId;

    @Column(name = "provider_id", columnDefinition = "UUID")
    private UUID providerId;

    @Column(nullable = false, length = 16)
    private String adapter;

    @Column(name = "sections_json", nullable = false, columnDefinition = "TEXT")
    private String sectionsJson;

    /** Section ids and the reason each was dropped. */
    @Column(name = "dropped_json", columnDefinition = "TEXT")
    private String droppedJson;

    /** Unavailable sections and the reason each degraded. */
    @Column(name = "degraded_json", columnDefinition = "TEXT")
    private String degradedJson;

    @Column(name = "max_chars", nullable = false)
    private int maxChars;

    @Column(name = "estimated_tokens", nullable = false)
    private int estimatedTokens;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }
}
