package io.aria.conductor.memory.dto;

import io.aria.conductor.common.model.Sensitivity;

/**
 * Record one fact about an entity. {@code sourceRef} is the provenance pointer
 * (tool call, message, extraction job); it is required for RUN writes, is written to the
 * assertion, and is part of an extraction proposal's idempotency hash.
 */
public record AddFactCommand(
        String factKey,
        String valueText,
        String valueType,
        Sensitivity sensitivity,
        String sourceRef) {
}
