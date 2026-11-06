package io.aria.conductor.common.security;

import java.time.Instant;
import java.util.UUID;

/**
 * Transport-owned identity of the caller performing a platform operation
 * (spec §6.2). Two authorities exist and are never interchangeable: a
 * single OPERATOR for UI/REST operator actions, and a run-scoped WORKER for
 * the agents a run launches. Identity is resolved by the transport or the
 * authenticated session, never from caller-supplied JSON.
 *
 * <p>This is not a multi-user RBAC model: there is exactly one operator
 * credential per installation and worker grants are bound to one run.
 *
 * @param role      the authority this actor holds
 * @param runId     the run a WORKER is bound to; {@code null} for OPERATOR
 * @param expiresAt credential/session expiry; {@code null} only for the
 *                  configuration-managed operator bearer credential, which is
 *                  revoked by configuration rather than by expiry
 */
public record ActorPrincipal(Role role, UUID runId, Instant expiresAt) {

    public enum Role {
        OPERATOR,
        WORKER
    }

    /** Operator acting through the configured bearer credential (no token expiry). */
    public static ActorPrincipal operator(Instant expiresAt) {
        return new ActorPrincipal(Role.OPERATOR, null, expiresAt);
    }

    /** Run-scoped worker credential; always carries an explicit expiry. */
    public static ActorPrincipal worker(UUID runId, Instant expiresAt) {
        return new ActorPrincipal(Role.WORKER, runId, expiresAt);
    }

    /**
     * Guards operator-only actions (core selection, workspace admission,
     * credential management, approval decisions, retirement).
     *
     * @throws SecurityException with the fixed message the API maps to 403
     */
    public void requireOperator() {
        if (role != Role.OPERATOR) {
            throw new SecurityException("Operator authority required");
        }
    }

    /**
     * Guards operations scoped to one run: the actor must be the worker of
     * exactly that run.
     *
     * @throws SecurityException when the actor is not bound to {@code expectedRunId}
     */
    public void requireRun(UUID expectedRunId) {
        if (role != Role.WORKER || runId == null || !runId.equals(expectedRunId)) {
            throw new SecurityException("Run scope mismatch");
        }
    }

    /** True when the credential has an expiry that is already past. */
    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    /** @throws SecurityException when the credential/session has expired */
    public void requireActive(Instant now) {
        if (isExpired(now)) {
            throw new SecurityException("Actor credential expired");
        }
    }
}
