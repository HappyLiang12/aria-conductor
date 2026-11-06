package io.aria.conductor.execution.security;

import io.aria.conductor.common.security.ActorPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single-operator authentication boundary (spec §6.2). The operator
 * credential is configured separately ({@code aria.operator.bearer-token},
 * falling back to the {@code ARIA_OPERATOR_BEARER_TOKEN} environment variable)
 * and is never the worker token or a core secret. It authenticates only the
 * session endpoint, which issues a random opaque session plus a CSRF token;
 * cookies are HttpOnly/SameSite and every cookie-authenticated mutation must
 * carry a matching CSRF token and an allowed Origin.
 *
 * <p>With no credential configured the boundary fails closed: session creation
 * answers 401 instead of accepting anything.
 */
@Service
public class OperatorSessionService {

    public static final String COOKIE_NAME = "aria_operator_session";
    public static final String CSRF_HEADER = "X-CSRF-Token";
    public static final Duration DEFAULT_SESSION_TTL = Duration.ofHours(8);
    /**
     * Loopback spellings, any port: the dashboard may be opened on localhost or
     * 127.0.0.1 (the same local operator), and Vite picks the next free port when
     * the configured one is taken. Entries are exact origins, or
     * {@code http://host:*} to accept any port of that host -- the same shape the
     * dev CORS policies use. An operator-supplied exact list stays exact.
     */
    public static final String DEFAULT_ALLOWED_ORIGINS = "http://localhost:*,http://127.0.0.1:*";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final int SESSION_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String operatorCredential;
    private final Duration sessionTtl;
    /** The one origin parser/matcher, shared with every other guarded layer. */
    private final io.aria.conductor.common.security.OperatorOriginGuard originGuard;
    private final boolean cookieSecure;
    private final Clock clock;
    private final ConcurrentHashMap<String, OperatorSession> sessions = new ConcurrentHashMap<>();

    @Autowired
    public OperatorSessionService(
            @Value("${aria.operator.bearer-token:${ARIA_OPERATOR_BEARER_TOKEN:}}") String operatorCredential,
            @Value("${aria.operator.session-ttl:PT8H}") Duration sessionTtl,
            @Value("${aria.operator.allowed-origins:" + DEFAULT_ALLOWED_ORIGINS + "}") String allowedOrigins,
            @Value("${aria.operator.cookie-secure:false}") boolean cookieSecure) {
        this(operatorCredential, sessionTtl, allowedOrigins, cookieSecure, Clock.systemUTC());
    }

    /** Test/override seam: an explicit clock and pre-parsed configuration. */
    public OperatorSessionService(String operatorCredential, Duration sessionTtl, String allowedOrigins,
                                  boolean cookieSecure, Clock clock) {
        this.operatorCredential = operatorCredential == null ? "" : operatorCredential.trim();
        this.sessionTtl = sessionTtl == null ? DEFAULT_SESSION_TTL : sessionTtl;
        this.cookieSecure = cookieSecure;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.originGuard = new io.aria.conductor.common.security.OperatorOriginGuard(
                allowedOrigins == null ? "" : allowedOrigins);
    }

    /** True when the operator credential is provisioned; otherwise every session request is 401. */
    public boolean isConfigured() {
        return !operatorCredential.isEmpty();
    }

    /**
     * Constant-time verification of the configured operator bearer credential.
     * Always false when no credential is configured, so the boundary fails closed.
     */
    public boolean verifyOperatorCredential(String authorizationHeader) {
        if (!isConfigured() || authorizationHeader == null) {
            return false;
        }
        String presented = authorizationHeader.trim();
        if (!presented.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return false;
        }
        return constantTimeEquals(presented.substring(BEARER_PREFIX.length()).trim(), operatorCredential);
    }

    /** Issues a new operator session (random id + CSRF token, explicit expiry). */
    public OperatorSession createSession() {
        purgeExpired();
        byte[] id = new byte[SESSION_BYTES];
        byte[] csrf = new byte[SESSION_BYTES];
        RANDOM.nextBytes(id);
        RANDOM.nextBytes(csrf);
        OperatorSession session = new OperatorSession(
                Base64.getUrlEncoder().withoutPadding().encodeToString(id),
                Base64.getUrlEncoder().withoutPadding().encodeToString(csrf),
                clock.instant().plus(sessionTtl));
        sessions.put(session.sessionId(), session);
        return session;
    }

    /** Resolves a still-valid session; expired sessions are dropped on sight. */
    public Optional<OperatorSession> findSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        OperatorSession session = sessions.get(sessionId);
        if (session == null) {
            return Optional.empty();
        }
        if (!session.expiresAt().isAfter(clock.instant())) {
            sessions.remove(sessionId);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    /** Operator principal for a valid session cookie. */
    public Optional<ActorPrincipal> authenticateSession(String sessionId) {
        return findSession(sessionId).map(session -> ActorPrincipal.operator(session.expiresAt()));
    }

    /** Revokes one session (the UI "sign out" path); core credentials are untouched. */
    public void revokeSession(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    /**
     * Validates a cookie-authenticated mutation: Origin must be one of the
     * configured origins and the CSRF header must match the session's token.
     *
     * @throws ForbiddenMutationException when either check fails (HTTP 403 at the boundary)
     */
    public void validateMutation(OperatorSession session, String csrfHeader, String origin) {
        // The session-cookie contract is STRICTER than the loopback/ungated one:
        // a cookie is browser-present by definition, so the Origin is REQUIRED
        // and must be allowed (the null-Origin local-tool exemption does not
        // apply — a missing Origin here is itself a forged request).
        if (!isAllowedOrigin(origin)) {
            throw new ForbiddenMutationException("Cross-origin operator mutation rejected");
        }
        if (!constantTimeEquals(csrfHeader, session.csrfToken())) {
            throw new ForbiddenMutationException("CSRF validation failed");
        }
    }

    /**
     * Refuses a MUTATION whose Origin names a web page rather than a local tool
     * (fleet runtime governance review): every browser POST carries an Origin
     * header, local tools send none — so an Origin that is present but not one
     * of the operator's trusted front ends means a cross-site page the operator
     * had open forged the request (CORS cannot block a "simple" POST, and not
     * reading the response is no protection). A MISSING Origin is allowed: that
     * is the local-tool path the loopback contract exists for. Used by the
     * loopback auto-authority branch AND by the deliberately unauthenticated
     * operator routes (/answer, /finalize) as their only browser-grade defense.
     * The session-cookie path keeps its stricter contract (Origin REQUIRED and
     * allowed, plus CSRF) via {@link #validateMutation}.
     */
    public void rejectCrossSiteOrigin(String origin) {
        if (origin == null) {
            return; // local tools send no Origin: the loopback contract's audience
        }
        if (!isAllowedOrigin(origin)) {
            throw new ForbiddenMutationException("Cross-origin operator mutation rejected");
        }
    }

    /** Whether an Origin header names an operator-trusted front end (null never does). */
    public boolean isAllowedOrigin(String origin) {
        return originGuard.isAllowed(origin);
    }

    public boolean isCookieSecure() {
        return cookieSecure;
    }

    public Duration sessionTtl() {
        return sessionTtl;
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        sessions.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return false;
        }
        // Compare digests so the comparison is length-independent.
        return MessageDigest.isEqual(sha256(a), sha256(b));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** One operator session: opaque id, bound CSRF token, explicit expiry. */
    public record OperatorSession(String sessionId, String csrfToken, Instant expiresAt) {
    }

    /** Cookie-authenticated mutation rejected by Origin/CSRF validation (HTTP 403). */
    public static class ForbiddenMutationException extends SecurityException {
        public ForbiddenMutationException(String message) {
            super(message);
        }
    }
}
