package io.aria.conductor.dashboard.memory.dto;

import io.aria.conductor.memory.dto.SupersedeFactCommand;

/** Request body of {@code POST /api/v1/memory/facts/{id}/supersede}. */
public record SupersedeFactRequest(
        String valueText,
        long expectedVersion,
        String sourceRef) {

    public SupersedeFactCommand toCommand() {
        return new SupersedeFactCommand(valueText, expectedVersion, sourceRef);
    }
}
