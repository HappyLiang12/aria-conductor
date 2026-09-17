package io.aria.conductor.mcp;

import io.aria.conductor.execution.approval.RunScopedCredentialService;
import io.aria.conductor.execution.approval.WorkerScope;
import io.aria.conductor.execution.mcp.McpProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * Single authority for classifying a presented bearer token into exactly one MCP
 * caller identity (C4 ruling 2).
 *
 * <p>Two call sites share this class, so the operator comparison and the worker
 * lookup can never drift apart:
 * <ul>
 *   <li>{@link McpTokenFilter} resolves the {@code Authorization} header of every
 *       protected HTTP request; an empty result means 401.</li>
 *   <li>{@code McpServerConfig}'s identity-binding tool callback re-resolves the
 *       same header from the MCP transport context, because the servlet thread's
 *       {@link McpCallerContext} ThreadLocal does not survive onto the transport's
 *       async invocation thread (C4 ruling 3).</li>
 * </ul>
 *
 * <p>Resolution (identical in both auth modes, fail closed):
 * <ul>
 *   <li>no header: OPERATOR in none mode (legacy behavior retained), empty in
 *       token mode (frozen 401);</li>
 *   <li>header byte-for-byte equal to {@code "Bearer " + aria.mcp.token}:
 *       OPERATOR (frozen comparison, see {@link #isOperatorBearer});</li>
 *   <li>{@code Bearer <opaque>} resolving through
 *       {@link RunScopedCredentialService}: WORKER with its live scope;</li>
 *   <li>anything else: empty — the caller rejects with 401. There is no
 *       fallback to anonymous access (design section 6.2).</li>
 * </ul>
 *
 * <p>Presented token material is never logged and never retained.
 */
@Component
public class WorkerScopeResolver {

    private static final String BEARER_PREFIX = "Bearer ";

    private final McpProperties properties;
    private final RunScopedCredentialService credentials;

    public WorkerScopeResolver(McpProperties properties, RunScopedCredentialService credentials) {
        if (properties == null) {
            throw new IllegalArgumentException("properties is required");
        }
        if (credentials == null) {
            throw new IllegalArgumentException("credentials is required");
        }
        this.properties = properties;
        this.credentials = credentials;
    }

    /**
     * Classifies a raw {@code Authorization} header into a caller identity, or
     * empty when the header must be rejected. Mode-aware: {@code null} means "no
     * header presented" and is only operator-equivalent in none mode.
     */
    public Optional<McpCallerContext.Caller> resolveAuthorization(String authorizationHeader) {
        if (authorizationHeader == null) {
            // Token mode keeps requiring the bearer; none mode stays operator-equivalent.
            return properties.isTokenMode()
                    ? Optional.empty()
                    : Optional.of(McpCallerContext.Caller.operator());
        }
        if (isOperatorBearer(authorizationHeader)) {
            return Optional.of(McpCallerContext.Caller.operator());
        }
        if (!authorizationHeader.startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }
        String presented = authorizationHeader.substring(BEARER_PREFIX.length());
        if (presented.isBlank()) {
            return Optional.empty();
        }
        return resolve(presented).map(McpCallerContext.Caller::worker);
    }

    /**
     * Worker-only lookup: token material (no {@code Bearer } prefix) to its live
     * scope. An unknown, expired, revoked or run-terminated token is empty.
     */
    public Optional<WorkerScope> resolve(String token) {
        return credentials.resolve(token);
    }

    /**
     * Frozen operator comparison: byte-for-byte equality with
     * {@code "Bearer " + aria.mcp.token}. A blank configured token never
     * authenticates anything (token mode refuses to start with one).
     */
    private boolean isOperatorBearer(String actual) {
        String token = properties.getToken();
        if (token == null || token.isBlank()) {
            return false;
        }
        String expected = BEARER_PREFIX + token;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
