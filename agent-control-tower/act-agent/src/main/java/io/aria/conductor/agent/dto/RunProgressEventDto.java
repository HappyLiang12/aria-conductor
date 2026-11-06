package io.aria.conductor.agent.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One persisted progress observation, as {@code GET /api/v1/runs/{id}/progress}
 * replays it.
 *
 * <p>The list is the ordered log of observations, NOT the merged view: one part that
 * grew between two polls is two entries with the same {@code partId} and increasing
 * {@code seq}, and the client merges them into one line. Collapsing them here instead
 * would make the live stream and the replay disagree about which row is newest, and
 * would throw away the history the table exists to keep.
 *
 * @param partId the stable identity of the observed part; null for a row written before
 *               V74 and for a core that reported no part id, in which case {@code seq}
 *               is the consumer's only identity
 */
public record RunProgressEventDto(
        UUID id,
        UUID runId,
        UUID agentId,
        int iteration,
        String kind,
        long seq,
        String content,
        String toolName,
        String partId,
        Instant createdAt) {
}
