package io.aria.conductor.mcp;

import io.aria.conductor.execution.mcp.McpProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Identity guard for the MCP HTTP surfaces, active in BOTH auth modes
 * (C4 ruling 2). It resolves every protected request through
 * {@link WorkerScopeResolver} to exactly one of:
 *
 * <ul>
 *   <li><b>OPERATOR</b> — the operator bearer matches byte-for-byte (token mode,
 *       frozen behaviour), or no Authorization header is present in none mode
 *       (v1 behaviour unchanged).</li>
 *   <li><b>WORKER(scope)</b> — the bearer resolves to a live worker credential,
 *       on the streamable endpoint {@code /mcp} only, where the per-request
 *       transport context lets the governance aspect re-bind the identity at the
 *       tool seam (ruling 3). The SSE surfaces ({@code /sse},
 *       {@code /mcp/message}) carry no per-request transport context, so a worker
 *       credential there would be enforced nowhere: it is rejected 401 rather
 *       than admitted unenforced (fail closed).</li>
 *   <li><b>401</b> — token mode without a bearer (frozen), or any present bearer
 *       that is neither the operator token nor a live worker credential.
 *       Rejecting a present-but-invalid bearer in none mode is the deliberate
 *       C4 fail-closed change: previously any header was ignored.</li>
 * </ul>
 *
 * <p>The resolved identity is bound to the request thread via
 * {@link McpCallerContext} and cleared in a {@code finally} block. Nothing here
 * is ever logged, and the presented token material is never retained.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class McpTokenFilter extends OncePerRequestFilter {

    private static final List<String> PROTECTED_PATHS = List.of("/mcp", "/mcp/message", "/sse");

    /** The only surface that can re-bind identity at the tool seam (ruling 3). */
    private static final String STREAMABLE_PATH = "/mcp";

    private final McpProperties properties;
    private final WorkerScopeResolver workerScopeResolver;

    public McpTokenFilter(McpProperties properties, WorkerScopeResolver workerScopeResolver) {
        if (properties.isTokenMode() && (properties.getToken() == null || properties.getToken().isBlank())) {
            throw new IllegalStateException(
                    "aria.mcp.token must be set when aria.mcp.auth-mode=token (refusing a guessable empty bearer)");
        }
        this.properties = properties;
        this.workerScopeResolver = workerScopeResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!PROTECTED_PATHS.contains(path)) {
            chain.doFilter(request, response);
            return;
        }
        Optional<McpCallerContext.Caller> identity =
                workerScopeResolver.resolveAuthorization(request.getHeader("Authorization"));
        if (identity.isEmpty() || !transportCanEnforce(identity.get(), path)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        McpCallerContext.set(identity.get());
        try {
            chain.doFilter(request, response);
        } finally {
            McpCallerContext.clear();
        }
    }

    /**
     * Worker identities are only admitted where the streamable transport can
     * propagate them to the tool invocation (ruling 3). Operator access keeps
     * working on every protected path.
     */
    private static boolean transportCanEnforce(McpCallerContext.Caller caller, String path) {
        return !caller.isWorker() || STREAMABLE_PATH.equals(path);
    }
}
