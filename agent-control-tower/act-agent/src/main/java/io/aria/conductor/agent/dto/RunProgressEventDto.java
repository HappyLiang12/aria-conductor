package io.aria.conductor.agent.dto;

import java.time.Instant;
import java.util.UUID;

public record RunProgressEventDto(
        UUID id,
        UUID runId,
        UUID agentId,
        int iteration,
        String kind,
        long seq,
        String content,
        String toolName,
        Instant createdAt) {
}
