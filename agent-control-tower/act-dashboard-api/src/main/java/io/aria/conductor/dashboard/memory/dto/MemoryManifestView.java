package io.aria.conductor.dashboard.memory.dto;

import java.time.Instant;
import java.util.List;

/**
 * One context assembly (spec §9): the metadata of a turn's prompt envelope, never the
 * prompt. {@code sections} is a whitelist of id/sources/size/truncation; anything else in
 * the stored JSON is dropped at the mapping boundary so a prompt body can never leak.
 */
public record MemoryManifestView(
        String id,
        String runId,
        int turnNumber,
        String conversationId,
        String agentId,
        String providerId,
        String adapter,
        int maxChars,
        int estimatedTokens,
        Instant createdAt,
        List<MemoryManifestSection> sections,
        List<String> dropped,
        List<String> degraded) {
}
