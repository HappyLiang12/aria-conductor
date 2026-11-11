package io.aria.conductor.memory.dto;

import io.aria.conductor.memory.model.MemoryActorType;

import java.util.Objects;

/**
 * Who is writing memory and under which reference. The type selects the row of the
 * governance matrix; the reference is the audit pointer (operator id, run id, extraction
 * job id) recorded on every assertion.
 */
public record MemoryActor(MemoryActorType type, String ref) {

    public MemoryActor {
        Objects.requireNonNull(type, "memory actor type is required");
    }
}
