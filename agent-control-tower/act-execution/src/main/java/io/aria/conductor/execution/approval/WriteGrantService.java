package io.aria.conductor.execution.approval;

import io.aria.conductor.common.model.AcpPermissionRequest;
import io.aria.conductor.common.repository.AcpPermissionRequestRepository;
import io.aria.conductor.common.security.ActorPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One-use write grants (spec §6.3). A grant authorizes exactly one matching
 * platform MCP operation: {@link #consume(UUID, String, String)} returns
 * {@code true} for the first call whose {@code (run, tool, arguments digest)}
 * matches an unexpired grant and {@code false} for every replay, changed
 * argument document, different run and expired grant. Denials and native
 * (core-executed) allowances never create a grant, so a native write can never
 * be repeated a second time through the platform MCP boundary.
 *
 * <p>Neither method accepts a caller-supplied actor identity: a grant is bound
 * to the run, and {@link #grant(ActorPrincipal, UUID, String, String, Instant)}
 * requires the authenticated operator principal that the decision surface
 * resolved from its own transport. The grant is a ledger row (kind
 * {@code WRITE_GRANT}, correlation key {@code platform-mcp / grant:<digest>}),
 * so the one-use property is enforced by the database: {@code consumed_at} is
 * written once under the row's optimistic lock, and a lost race reports the
 * call as unauthorized instead of granting a second use.
 */
@Service
public class WriteGrantService {

    /** Correlation session of the platform MCP boundary (never a core session id). */
    public static final String GRANT_SESSION_ID = "platform-mcp";

    private static final String DIGEST_PREFIX = "sha256:";
    private static final String GRANT_REQUEST_PREFIX = "grant:";

    private final AcpPermissionRequestRepository permissions;
    private final Clock clock;

    @Autowired
    public WriteGrantService(AcpPermissionRequestRepository permissions) {
        this(permissions, Clock.systemUTC());
    }

    /** Test/override seam: every expiry check uses this clock. */
    public WriteGrantService(AcpPermissionRequestRepository permissions, Clock clock) {
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Operator-authorized one-use authorization for exactly one matching call.
     *
     * @throws SecurityException when the principal is not an active operator
     */
    public void grant(ActorPrincipal operator, UUID runId, String toolName, String argumentsDigest,
                      Instant expiresAt) {
        ActorPrincipal principal = Objects.requireNonNull(operator, "operator");
        principal.requireOperator();
        principal.requireActive(clock.instant());
        grant(runId, toolName, argumentsDigest, expiresAt);
    }

    /**
     * Ledger primitive behind {@link #grant(ActorPrincipal, UUID, String, String, Instant)}
     * and the coordinator's decision path (which has already verified the
     * operator): records — or refreshes — the single authorization for the
     * identical {@code (run, tool, digest)} call. A refresh replaces the
     * previous, already consumed authorization; it never accumulates a second
     * outstanding use for the identical call.
     *
     * <p>Package-visible on purpose: the only production caller is the
     * permission coordinator, which reaches it after {@code requireOperator()},
     * so an authorization can never be created without an authenticated operator.
     */
    synchronized void grant(UUID runId, String toolName, String argumentsDigest, Instant expiresAt) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(argumentsDigest, "argumentsDigest");
        Instant expiry = Objects.requireNonNull(expiresAt, "expiresAt");
        Instant now = clock.instant();
        if (!expiry.isAfter(now)) {
            throw new IllegalArgumentException("A write grant must expire in the future: " + expiry);
        }

        Optional<AcpPermissionRequest> existing = permissions.findByRunIdAndSessionIdAndRequestId(
                runId, GRANT_SESSION_ID, grantRequestId(argumentsDigest));
        AcpPermissionRequest grant = existing.orElseGet(() -> AcpPermissionRequest.builder()
                .id(UUID.randomUUID())
                .runId(runId)
                .sessionId(GRANT_SESSION_ID)
                .requestId(grantRequestId(argumentsDigest))
                .createdAt(now)
                .build());
        if (existing.isPresent() && !toolName.equals(grant.getToolName())) {
            throw new IllegalStateException("The identical argument document of run " + runId
                    + " is already authorized for tool " + grant.getToolName()
                    + "; a grant for " + toolName + " is refused");
        }
        grant.setKind(AcpPermissionRequest.Kind.WRITE_GRANT);
        grant.setToolName(toolName);
        grant.setTarget(PermissionTarget.PLATFORM_MCP.name());
        grant.setArgumentsDigest(argumentsDigest);
        grant.setExpiresAt(expiry);
        grant.setConsumedAt(null);
        grant.setDeliveryState(PermissionDeliveryState.DELIVERED.name());
        grant.setDeliveredAt(now);
        permissions.save(grant);
    }

    /**
     * Consumes the run's authorization for exactly this {@code (tool, digest)}
     * call.
     *
     * <p>Calling contract: this method must not be invoked inside a
     * caller-managed transaction — its {@code @Version} check maps a lost race
     * to {@code false} only when it owns the flush; the execution boundary that
     * consumes grants owns this contract.
     *
     * @return {@code true} only for the first matching, unexpired, unconsumed
     *         grant; every other call — replay, changed arguments, another run,
     *         an expired grant, no grant at all — is refused
     */
    public synchronized boolean consume(UUID runId, String toolName, String argumentsDigest) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(argumentsDigest, "argumentsDigest");

        Optional<AcpPermissionRequest> found = permissions.findByRunIdAndSessionIdAndRequestId(
                runId, GRANT_SESSION_ID, grantRequestId(argumentsDigest));
        if (found.isEmpty()) {
            return false;
        }
        AcpPermissionRequest grant = found.get();
        if (grant.getKind() != AcpPermissionRequest.Kind.WRITE_GRANT
                || !toolName.equals(grant.getToolName())
                || grant.getConsumedAt() != null
                || !grant.getExpiresAt().isAfter(clock.instant())) {
            return false;
        }
        grant.setConsumedAt(clock.instant());
        try {
            permissions.save(grant);
        } catch (OptimisticLockingFailureException e) {
            // A concurrent consumer won the single use; this call is unauthorized.
            return false;
        }
        return true;
    }

    /** The ledger correlation request id of a platform grant. */
    static String grantRequestId(String argumentsDigest) {
        return GRANT_REQUEST_PREFIX + argumentsDigest;
    }

    /**
     * The argument digest a grant is bound to: {@code sha256:<hex>} over the
     * UTF-8 bytes of the argument document. The platform boundary computes the
     * same digest from the native call, so a changed argument document can
     * never consume an existing grant.
     */
    public static String digestOf(String argumentsJson) {
        String document = argumentsJson == null ? "" : argumentsJson;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(document.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return DIGEST_PREFIX + hex;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
