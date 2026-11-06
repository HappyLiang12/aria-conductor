package io.aria.conductor.execution.security;

import io.aria.conductor.common.security.ActorPrincipal;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Run-scoped worker credentials (spec §6.2). A worker token is a random opaque
 * 256-bit value minted for exactly one run with an explicit expiry; only its
 * SHA-256 hash is retained server-side, so a token database dump cannot be
 * replayed. Tokens are invalidated with their run.
 *
 * <p>Expiry/cancellation paths call {@link #issueWorker(UUID, Instant)} and
 * {@link #revokeRun(UUID)} in-process as ordinary service methods: there is
 * deliberately no external SYSTEM token a caller could forge to mint or revoke
 * worker credentials.
 */
@Service
public class ActorTokenService {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final int TOKEN_BYTES = 32;
    /** Recently invalidated token hashes, only so rejection reports a precise reason. */
    private static final int MAX_INVALIDATED_TOKENS_TRACKED = 4096;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Clock clock;
    private final ConcurrentHashMap<String, WorkerGrant> workerGrants = new ConcurrentHashMap<>();
    private final Map<String, InvalidReason> invalidatedTokenHashes = Collections.synchronizedMap(
            new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, InvalidReason> eldest) {
                    return size() > MAX_INVALIDATED_TOKENS_TRACKED;
                }
            });

    private enum InvalidReason {
        REVOKED,
        EXPIRED
    }

    public ActorTokenService() {
        this(Clock.systemUTC());
    }

    /** Test/override seam: all expiry checks use this clock. */
    public ActorTokenService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Mints a fresh opaque worker token bound to {@code runId}. Callers (the run
     * lifecycle) hand it only to the processes of that run; it must never reach
     * the operator credential, a frontend asset or another run's environment.
     */
    public String issueWorker(UUID runId, Instant expiresAt) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(expiresAt, "expiresAt");
        byte[] raw = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        workerGrants.put(hash(token), new WorkerGrant(runId, expiresAt));
        return token;
    }

    /**
     * Verifies a run-scoped worker bearer credential.
     *
     * @param authorizationHeader the raw {@code Authorization} header value
     * @return the verified, run-bound principal
     * @throws InvalidCredentialException when the credential is absent, malformed,
     *                                    unknown, expired or revoked (HTTP 401 at the boundary)
     */
    public ActorPrincipal authenticateBearer(String authorizationHeader) {
        String token = extractToken(authorizationHeader)
                .orElseThrow(() -> new InvalidCredentialException("Missing or invalid actor credential"));
        String key = hash(token);
        WorkerGrant grant = workerGrants.get(key);
        if (grant == null) {
            InvalidReason reason = invalidatedTokenHashes.get(key);
            if (reason == InvalidReason.REVOKED) {
                throw new InvalidCredentialException("Actor token revoked");
            }
            if (reason == InvalidReason.EXPIRED) {
                throw new InvalidCredentialException("Actor token expired");
            }
            throw new InvalidCredentialException("Invalid actor token");
        }
        if (!grant.expiresAt().isAfter(clock.instant())) {
            workerGrants.remove(key);
            invalidatedTokenHashes.put(key, InvalidReason.EXPIRED);
            throw new InvalidCredentialException("Actor token expired");
        }
        return ActorPrincipal.worker(grant.runId(), grant.expiresAt());
    }

    /** Non-throwing form of {@link #authenticateBearer(String)} for transports. */
    public Optional<ActorPrincipal> resolveBearer(String authorizationHeader) {
        try {
            return Optional.of(authenticateBearer(authorizationHeader));
        } catch (InvalidCredentialException e) {
            return Optional.empty();
        }
    }

    /** Invalidates every worker token of {@code runId}. */
    public void revokeRun(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        workerGrants.entrySet().removeIf(entry -> {
            if (entry.getValue().runId().equals(runId)) {
                invalidatedTokenHashes.put(entry.getKey(), InvalidReason.REVOKED);
                return true;
            }
            return false;
        });
    }

    private static Optional<String> extractToken(String authorizationHeader) {
        if (authorizationHeader == null) {
            return Optional.empty();
        }
        String header = authorizationHeader.trim();
        if (header.length() <= BEARER_PREFIX.length()
                || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return Optional.empty();
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record WorkerGrant(UUID runId, Instant expiresAt) {
    }

    /** Unauthenticated actor credential (HTTP 401 at the boundary). */
    public static class InvalidCredentialException extends SecurityException {
        public InvalidCredentialException(String message) {
            super(message);
        }
    }
}
