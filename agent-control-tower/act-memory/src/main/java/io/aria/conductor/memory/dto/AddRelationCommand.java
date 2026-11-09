package io.aria.conductor.memory.dto;

import io.aria.conductor.common.model.Sensitivity;

import java.util.UUID;

/**
 * Record one typed edge between two entities. Entities are referenced by id only — there
 * is no foreign key across (or inside) the module, so both must already exist.
 */
public record AddRelationCommand(
        UUID fromEntityId,
        String predicate,
        UUID toEntityId,
        Sensitivity sensitivity,
        String sourceRef) {
}
