package io.aria.conductor.dashboard.memory.dto;

import java.time.Instant;

/**
 * One row of the append-only assertion timeline. The before/after payloads are audit
 * metadata written by the memory services; they never contain credentials.
 */
public record MemoryAssertionView(
        String id,
        String subjectType,
        String subjectId,
        String action,
        String actorType,
        String actorRef,
        String sourceRef,
        String beforeJson,
        String afterJson,
        Instant createdAt) {
}
