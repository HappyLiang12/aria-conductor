package io.aria.conductor.memory.dto;

import io.aria.conductor.common.model.Sensitivity;

/**
 * One deterministic recall request (spec §8.4). {@code text} is the goal plus the last
 * three user turns; {@code focusRef} is the run's workspace or repository reference, which
 * names an entity even when the text does not. The ceiling defaults fail-closed to
 * {@link Sensitivity#INTERNAL}.
 */
public record RecallQuery(String text, Sensitivity maxSensitivity, String focusRef) {

    public RecallQuery {
        text = text == null ? "" : text;
        maxSensitivity = maxSensitivity == null ? Sensitivity.INTERNAL : maxSensitivity;
        focusRef = focusRef == null ? "" : focusRef;
    }
}
