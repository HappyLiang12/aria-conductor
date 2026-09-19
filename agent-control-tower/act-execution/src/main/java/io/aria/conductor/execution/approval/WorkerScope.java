package io.aria.conductor.execution.approval;

import java.time.Instant;
import java.util.UUID;

/**
 * C4 frozen shape: what a run-scoped worker credential grants. The worker is
 * bound to exactly one run and the agent that owns it, until {@code expiresAt}
 * (already capped by {@link RunScopedCredentialService#MAX_TTL} at issue time).
 *
 * <p>Consumed by the MCP worker authorization boundary (act-mcp): every request
 * authenticated with a worker credential resolves to this scope, and the
 * governance aspect denies operator-only tools, cross-run/agent scope mismatch
 * and un-granted writes for the run.
 */
public record WorkerScope(UUID runId, UUID agentId, Instant expiresAt) {

    public WorkerScope {
        if (runId == null) {
            throw new IllegalArgumentException("runId is required");
        }
        if (agentId == null) {
            throw new IllegalArgumentException("agentId is required");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt is required");
        }
    }
}
