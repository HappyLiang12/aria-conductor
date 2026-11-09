package io.aria.conductor.dashboard.memory.dto;

import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.memory.dto.CreateEntityCommand;

/** Request body of {@code POST /api/v1/memory/entities}. */
public record CreateEntityRequest(
        String kind,
        String canonicalKey,
        String displayName,
        Sensitivity sensitivity) {

    public CreateEntityCommand toCommand() {
        return new CreateEntityCommand(kind, canonicalKey, displayName, sensitivity);
    }
}
