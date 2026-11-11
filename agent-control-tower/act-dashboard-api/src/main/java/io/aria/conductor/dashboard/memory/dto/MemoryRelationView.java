package io.aria.conductor.dashboard.memory.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** Read view of one memory relation, carrying provenance, sensitivity and validity. */
public record MemoryRelationView(
        String id,
        String fromEntityId,
        String predicate,
        String toEntityId,
        String status,
        String origin,
        String sensitivity,
        BigDecimal confidence,
        Instant validFrom,
        Instant validTo) {
}
