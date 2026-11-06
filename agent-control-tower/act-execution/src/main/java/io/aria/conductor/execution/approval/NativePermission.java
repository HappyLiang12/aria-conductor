package io.aria.conductor.execution.approval;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A normalized permission request emitted by a core or by the platform MCP
 * boundary (spec §6.3). It is correlated to the run, runtime session and native
 * request id, carries the offered decision options, the tool identity and the
 * argument document, and expires no later than the run's own deadline.
 */
public record NativePermission(UUID runId, String sessionId, String requestId,
                               String toolName, PermissionTarget target, String argumentsJson,
                               List<PermissionOption> options, Instant expiresAt) {
    public NativePermission {
        options = List.copyOf(options);
    }
}
