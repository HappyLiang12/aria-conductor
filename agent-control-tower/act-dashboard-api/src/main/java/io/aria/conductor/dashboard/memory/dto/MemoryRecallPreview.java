package io.aria.conductor.dashboard.memory.dto;

import io.aria.conductor.memory.dto.MemoryEntityView;
import io.aria.conductor.memory.dto.MemoryFactView;

import java.util.List;

/**
 * Read-only recall preview (spec §9): the same ranked slice a prompt would receive,
 * carrying entity/fact provenance, sensitivity and validity. {@code truncated} says a
 * ranking cap was hit; it is never a degraded-run signal.
 */
public record MemoryRecallPreview(
        List<MemoryEntityView> entities,
        List<MemoryFactView> facts,
        boolean truncated) {
}
