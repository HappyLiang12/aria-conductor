package io.aria.conductor.execution.runtime;

import java.util.UUID;

/**
 * One normalized core event forwarded to Aria (progress, permission request,
 * usage, ...). The payload stays protocol-specific JSON so normalization never
 * loses information the governance layer may need.
 */
public record CoreEvent(String type, UUID runId, String sessionId,
        String requestId, String payloadJson) {
}
