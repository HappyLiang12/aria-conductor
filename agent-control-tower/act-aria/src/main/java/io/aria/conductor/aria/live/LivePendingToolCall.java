package io.aria.conductor.aria.live;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * One gated tool call waiting for the operator (spec §"Tools, approvals, live run"): the
 * run it parks, the approval the decision arrives under, and everything the approved call
 * needs to be re-executed — the tool name, its arguments and the bridge's call id, so the
 * {@code tool_result} answers the call the model actually made.
 *
 * <p>{@code arguments} is the bridge's object as it arrived; it is kept in memory only and
 * is never logged.
 */
record LivePendingToolCall(UUID runId, UUID approvalId, String toolName, String riskTier,
                           String callId, JsonNode arguments) {
}
