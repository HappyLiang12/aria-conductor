package io.aria.conductor.execution.approval;

/**
 * One decision option offered for a native permission request, normalized from
 * the core's own option kind (spec §6.3). {@code optionId} is the core's native
 * option id, so a decision selects a native option through verified semantics
 * (its kind) — never through its position and never by guessing a fixed id.
 */
public record PermissionOption(String optionId, PermissionChoice choice) {
}
