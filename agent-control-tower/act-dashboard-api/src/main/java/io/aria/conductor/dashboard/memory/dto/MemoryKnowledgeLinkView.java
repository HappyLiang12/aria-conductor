package io.aria.conductor.dashboard.memory.dto;

import java.time.Instant;

/**
 * Read view of one knowledge link. The knowledge item is a logical reference; the
 * document itself stays in act-knowledge.
 */
public record MemoryKnowledgeLinkView(
        String id,
        String entityId,
        String knowledgeItemId,
        String status,
        Instant createdAt,
        Instant decidedAt,
        String decidedBy) {
}
