package io.aria.conductor.dashboard.memory.dto;

import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.memory.dto.AddFactCommand;

/** Request body of {@code POST /api/v1/memory/entities/{id}/facts}. */
public record AddFactRequest(
        String factKey,
        String valueText,
        String valueType,
        Sensitivity sensitivity,
        String sourceRef) {

    public AddFactCommand toCommand() {
        return new AddFactCommand(factKey, valueText, valueType, sensitivity, sourceRef);
    }
}
