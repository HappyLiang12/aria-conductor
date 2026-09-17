package io.aria.conductor.execution.approval;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Run-scoped worker credentials (C4 ruling 5).
 *
 * <p>A worker credential is an opaque bearer token bound to one run. The
 * sandbox worker receives it when its run starts; the MCP surface resolves it
 * back to a {@link WorkerScope} (runId, agentId, expiry) on every request.
 *
 * <p>Properties, all by construction of this in-memory implementation:
 * <ul>
 *   <li><b>Opaque</b>: a token is 32 bytes from {@link SecureRandom},
 *       Base64url-encoded without padding, prefixed with {@code wcp_}. It
 *       carries no claims and can never be forged by decoding.</li>
 *   <li><b>TTL is capped</b>: the effective expiry is
 *       {@code min(requestedExpiry, now + MAX_TTL)} so a buggy or compromised
 *       caller cannot mint an unbounded credential. {@link #MAX_TTL} is the
 *       documented cap constant (30 minutes today).</li>
 *   <li><b>Never logged</b>: this class performs no logging at all. Tokens must
 *       not appear in any log line, audit event or exception message.</li>
 *   <li><b>Dies with the run</b>: {@link #resolve(String)} re-checks the run on
 *       every call; a token stops resolving as soon as the run reaches a
 *       terminal status ({@link RunStatus#COMPLETED}, {@link RunStatus#FAILED},
 *       {@link RunStatus#CANCELLED}, {@link RunStatus#ABORTED}) or the run row
 *       disappears. Terminal runs are also revoked eagerly where convenient.</li>
 *   <li><b>Restart invalidation</b>: the token store is process memory only.
 *       A restart (or a second instance) has never seen any token, so every
 *       previously issued credential is invalid after a restart. This is the
 *       intended trade-off: no credential survives a process, and no secret is
 *       persisted.</li>
 *   <li><b>One live token per run</b>: issuing a new token invalidates the
 *       previous one, so rotating a worker credential revokes the old value.</li>
 * </ul>
 */
@Service
public class RunScopedCredentialService {

    /** Documented maximum credential lifetime; requested expiries are capped here. */
    public static final Duration MAX_TTL = Duration.ofMinutes(30);

    /** Token prefix; identifies worker credentials in the request path. */
    static final String TOKEN_PREFIX = "wcp_";

    /** Random material per token. */
    private static final int TOKEN_BYTES = 32;

    private final RunRepository runRepository;
    private final Duration maxTtl;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    /** Token material -> issued credential. */
    private final ConcurrentMap<String, Issued> issuedByToken = new ConcurrentHashMap<>();

    /** Run id -> currently live token, so reissue can invalidate the previous one. */
    private final ConcurrentMap<UUID, String> tokenByRun = new ConcurrentHashMap<>();

    /**
     * Production wiring: system clock and the {@link #MAX_TTL} cap.
     * Annotated explicitly because the deterministic-collaborator constructor
     * below also exists (two constructors would otherwise be ambiguous).
     */
    @Autowired
    public RunScopedCredentialService(RunRepository runRepository) {
        this(runRepository, MAX_TTL, Clock.systemUTC());
    }

    /**
     * Deterministic-collaborator constructor for tests.
     *
     * @param maxTtl TTL cap, must not be null or negative. Production wiring
     *               always uses {@link #MAX_TTL}; this parameter exists so a
     *               test can pin capping behaviour without touching the constant.
     */
    RunScopedCredentialService(RunRepository runRepository, Duration maxTtl, Clock clock) {
        if (runRepository == null) {
            throw new IllegalArgumentException("runRepository is required");
        }
        if (maxTtl == null || maxTtl.isNegative() || maxTtl.isZero()) {
            throw new IllegalArgumentException("maxTtl must be positive");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock is required");
        }
        this.runRepository = runRepository;
        this.maxTtl = maxTtl;
        this.clock = clock;
    }

    /**
     * Issues a new credential for a run.
     *
     * <p>The effective expiry is {@code min(expiry, now + MAX_TTL)}. Issuing a
     * second credential for the same run invalidates the first one.
     *
     * @param runId  the run the worker belongs to
     * @param expiry requested expiry; must not be null
     * @return the opaque token material (never logged by this class)
     */
    public String issue(UUID runId, Instant expiry) {
        if (runId == null) {
            throw new IllegalArgumentException("runId is required");
        }
        if (expiry == null) {
            throw new IllegalArgumentException("expiry is required");
        }
        Instant cap = clock.instant().plus(maxTtl);
        Instant effectiveExpiry = expiry.isAfter(cap) ? cap : expiry;

        byte[] material = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(material);
        String token = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(material);

        String previous = tokenByRun.put(runId, token);
        if (previous != null) {
            issuedByToken.remove(previous);
        }
        issuedByToken.put(token, new Issued(runId, effectiveExpiry));
        return token;
    }

    /**
     * Resolves an opaque token to the worker scope it grants, or empty when the
     * token is unknown, expired, revoked, or its run is missing or terminal.
     * Expired/invalid tokens are dropped from the store as a side effect.
     */
    public Optional<WorkerScope> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Issued issued = issuedByToken.get(token);
        if (issued == null) {
            return Optional.empty();
        }
        if (!issued.expiresAt().isAfter(clock.instant())) {
            drop(token, issued.runId());
            return Optional.empty();
        }
        Optional<Run> run = runRepository.findById(issued.runId());
        if (run.isEmpty() || isTerminal(run.get().getStatus())) {
            drop(token, issued.runId());
            return Optional.empty();
        }
        return Optional.of(new WorkerScope(issued.runId(), run.get().getAgentId(), issued.expiresAt()));
    }

    /** Revokes the live credential of a run (if any). Null is a no-op. */
    public void revoke(UUID runId) {
        if (runId == null) {
            return;
        }
        String token = tokenByRun.remove(runId);
        if (token != null) {
            issuedByToken.remove(token);
        }
    }

    private void drop(String token, UUID runId) {
        issuedByToken.remove(token);
        tokenByRun.remove(runId, token);
    }

    /**
     * Exhaustive over {@link RunStatus}: adding a new status fails compilation
     * here so its terminality is decided deliberately (fail-closed review).
     */
    private static boolean isTerminal(RunStatus status) {
        return switch (status) {
            case PENDING, INITIALIZING, RUNNING, PAUSED -> false;
            case COMPLETED, FAILED, CANCELLED, ABORTED -> true;
        };
    }

    private record Issued(UUID runId, Instant expiresAt) {
    }
}
