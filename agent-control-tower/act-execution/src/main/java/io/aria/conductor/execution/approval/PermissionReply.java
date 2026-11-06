package io.aria.conductor.execution.approval;

import java.util.UUID;

/**
 * Answer to one native permission request, correlated by run, session and
 * request ID: which offered option the operator chose. Produced only for a
 * still-valid, correctly bound decision.
 */
public record PermissionReply(UUID runId, String sessionId,
        String requestId, String optionId) {
}
