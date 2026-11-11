package io.aria.conductor.memory.dto;

import io.aria.conductor.common.model.Sensitivity;

/**
 * Create one entity. The canonical key is the deterministic recall handle, so it is
 * required; {@code displayName} falls back to it and {@code sensitivity} to INTERNAL.
 */
public record CreateEntityCommand(
        String kind,
        String canonicalKey,
        String displayName,
        Sensitivity sensitivity) {
}
