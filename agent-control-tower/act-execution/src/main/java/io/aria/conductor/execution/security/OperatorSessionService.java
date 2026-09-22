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
    public static final String DEFAULT_ALLOWED_ORIGINS = "http://localhost:5173,http://localhost:8080";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final int SESSION_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String operatorCredential;
    private final Duration sessionTtl;
    private final Set<String> allowedOrigins;
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
        Set<String> origins = new LinkedHashSet<>();
        if (allowedOrigins != null) {
            Arrays.stream(allowedOrigins.split(",")).map(String::trim)
                    .filter(origin -> !origin.isEmpty()).forEach(origins::add);
        }
        this.allowedOrigins = Set.copyOf(origins);
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
        if (origin == null || !allowedOrigins.contains(origin.trim())) {
            throw new ForbiddenMutationException("Cross-origin operator mutation rejected");
        }
        if (!constantTimeEquals(csrfHeader, session.csrfToken())) {
            throw new ForbiddenMutationException("CSRF validation failed");
        }
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
