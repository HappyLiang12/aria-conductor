package io.aria.conductor.memory.dto;

import io.aria.conductor.memory.model.MemoryFact;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Read view of one fact with its provenance ({@code id} plus {@code entityId}),
 * sensitivity, and validity window. {@code origin} is the source authority string
 * (OPERATOR, RUN, EXTRACTION) the recall ranking uses.
 */
public record MemoryFactView(
        String id,
        String entityId,
        String factKey,
        String valueText,
        String valueType,
        String status,
        String origin,
        String sensitivity,
        BigDecimal confidence,
        Instant validFrom,
        Instant validTo,
        long version) {

    public static MemoryFactView from(MemoryFact fact) {
        return new MemoryFactView(
                fact.getId() == null ? null : fact.getId().toString(),
                fact.getEntityId() == null ? null : fact.getEntityId().toString(),
                fact.getFactKey(),
                fact.getValueText(),
                fact.getValueType(),
                fact.getStatus() == null ? null : fact.getStatus().name(),
                fact.getOrigin() == null ? null : fact.getOrigin().name(),
                fact.getSensitivity() == null ? null : fact.getSensitivity().name(),
                fact.getConfidence(),
                fact.getValidFrom(),
                fact.getValidTo(),
                fact.getVersion());
    }
}
