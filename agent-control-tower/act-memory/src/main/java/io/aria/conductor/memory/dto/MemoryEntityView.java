package io.aria.conductor.memory.dto;

import io.aria.conductor.memory.model.MemoryEntity;

import java.time.Instant;

/**
 * Read view of one memory entity with its provenance and lifecycle. Ids are strings so the
 * same value fits a REST body, a context section's source ids, and a manifest entry.
 */
public record MemoryEntityView(
        String id,
        String kind,
        String canonicalKey,
        String displayName,
        String sensitivity,
        String status,
        String origin,
        String mergedInto,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static MemoryEntityView from(MemoryEntity entity) {
        return new MemoryEntityView(
                entity.getId() == null ? null : entity.getId().toString(),
                entity.getKind(),
                entity.getCanonicalKey(),
                entity.getDisplayName(),
                entity.getSensitivity() == null ? null : entity.getSensitivity().name(),
                entity.getStatus() == null ? null : entity.getStatus().name(),
                entity.getOrigin() == null ? null : entity.getOrigin().name(),
                entity.getMergedInto() == null ? null : entity.getMergedInto().toString(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
