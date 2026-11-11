package io.aria.conductor.aria.controller;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The join key between a trajectory row's raw {@code tool_call_id} and the
 * {@code tool_calls} primary key.
 *
 * <p>{@code ToolCall} carries no raw-id column, so its id is derived rather than
 * stored: {@code RunTelemetryService} (act-execution) names the row
 * {@code UUID.nameUUIDFromBytes("tool-call:" + runId + ":" + toolCallId)}. That
 * derivation is private in the producer and this lane may not edit it, so the
 * restore path re-states it here. {@code ToolCallRowIdTest} pins the two together
 * by asserting that the id the telemetry service actually persists equals
 * {@link #of(UUID, String)}; a change to either derivation fails that test instead
 * of silently detaching restored tool status from its row.
 */
final class ToolCallRowId {

    private ToolCallRowId() {
    }

    /** The deterministic {@code tool_calls} id of {@code toolCallId} in {@code runId}. */
    static UUID of(UUID runId, String toolCallId) {
        return UUID.nameUUIDFromBytes(("tool-call:" + runId + ":" + toolCallId)
                .getBytes(StandardCharsets.UTF_8));
    }
}
