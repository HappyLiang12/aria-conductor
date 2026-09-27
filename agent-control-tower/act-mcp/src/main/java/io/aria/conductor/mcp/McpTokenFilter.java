package io.aria.conductor.mcp;

import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.execution.security.ActorTokenService;
import io.aria.conductor.execution.security.OperatorSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bearer guard for the MCP message endpoint (/mcp, /mcp/message) and the SSE
 * handshake (/sse). Two credential modes register it:
 *
 * <ul>
 *   <li>{@code aria.mcp.auth-mode=token} — legacy static bearer (v1 default
 *       behaviour, unchanged);</li>
 *   <li>{@code aria.mcp.auth-mode=actor} — every request must carry a valid
 *       run-scoped worker token; the MCP session is bound to that actor and any
 *       reuse of the session by a different actor is rejected with 403.
 *       Operator credentials are not valid here: the worker/operator boundary
 *       stays distinct on every route.</li>
 * </ul>
 *
 * <p>Session identifiers are read from both places a caller can put one, and
 * they must agree. The streamable transport reads the {@code Mcp-Session-Id}
 * header; the installed SDK's SSE message handler (mcp-spring-webmvc 0.18.3,
 * {@code WebMvcSseServerTransportProvider#handleMessage}) reads
 * {@code sessionId} from the <em>query parameter only</em> and ignores the
 * header. A caller could therefore pair a decoy header (which this filter would
 * bind) with a victim's query sessionId (which the transport would dispatch
 * into); the disagreement check rejects that split view with 403, and on
 * {@code /mcp/message} the query parameter is the binding key because it is
 * what the transport actually dispatches on.
 *
 * <p>{@code auth-mode=none} keeps the documented v1 no-filter default (audit
 * logging only); it is not an authenticated mode and is not a test override.
 */
@Component
@Conditional(McpTokenFilter.AuthModeEnabledCondition.class)
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class McpTokenFilter extends OncePerRequestFilter {

    /** Request attribute the resolved actor is published under; identity never comes from tool JSON. */
    public static final String ACTOR_ATTRIBUTE = "aria.actor";

    /** Streamable transport session header (SDK constant value). */
    static final String SESSION_HEADER = "Mcp-Session-Id";
    /** SSE transport session query parameter (the only place the SSE message handler reads it from). */
    static final String SSE_SESSION_PARAM = "sessionId";

    private static final String ACTOR_MODE = "actor";
    private static final String SSE_MESSAGE_ENDPOINT = "/mcp/message";
    private static final List<String> PROTECTED_PATHS = List.of("/mcp", SSE_MESSAGE_ENDPOINT, "/sse");

    private final McpProperties properties;
    private final ActorTokenService actorTokens;
    private final OperatorSessionService operatorSessions;
    private final ConcurrentHashMap<String, String> sessionActors = new ConcurrentHashMap<>();

    /** Legacy static-token filter (unit-test seam; v1 behaviour). */
    public McpTokenFilter(McpProperties properties) {
        this(properties, null, null);
    }

    /** Worker-token seam kept for the tests that wire no operator credential. */
    public McpTokenFilter(McpProperties properties, ObjectProvider<ActorTokenService> actorTokens) {
        this(properties, actorTokens, null);
    }

    @Autowired
    public McpTokenFilter(McpProperties properties, ObjectProvider<ActorTokenService> actorTokens,
                          ObjectProvider<OperatorSessionService> operatorSessions) {
        this.properties = properties;
        if (properties.isTokenMode() && (properties.getToken() == null || properties.getToken().isBlank())) {
            throw new IllegalStateException(
                    "aria.mcp.token must be set when aria.mcp.auth-mode=token (refusing a guessable empty bearer)");
        }
        this.actorTokens = actorTokens == null ? null : actorTokens.getIfAvailable();
        this.operatorSessions = operatorSessions == null ? null : operatorSessions.getIfAvailable();
        if (isActorMode(properties) && this.actorTokens == null) {
            throw new IllegalStateException(
                    "aria.mcp.auth-mode=actor requires an ActorTokenService bean (worker tokens are run-scoped, never static)");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!PROTECTED_PATHS.contains(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        if (properties.isTokenMode()) {
            if (!legacyTokenAccepted(request)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            chain.doFilter(request, response);
            return;
        }
        if (!isActorMode(properties)) {
            chain.doFilter(request, response);
            return;
        }

        ActorPrincipal actor = McpActorContext.resolveBearer(request.getHeader("Authorization"),
                actorTokens, operatorSessions);
        if (actor == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        String headerSession = normalized(request.getHeader(SESSION_HEADER));
        String querySession = normalized(request.getParameter(SSE_SESSION_PARAM));
        if (headerSession != null && querySession != null && !headerSession.equals(querySession)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        String sessionId = sessionIdFor(request, headerSession, querySession);
        if (sessionId != null) {
            String actorKey = actor.role() + ":" + actor.runId();
            String boundActor = sessionActors.putIfAbsent(sessionId, actorKey);
            if (boundActor != null && !boundActor.equals(actorKey)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
        }

        request.setAttribute(ACTOR_ATTRIBUTE, actor);
        chain.doFilter(request, response);

        if ("DELETE".equals(request.getMethod()) && sessionId != null) {
            sessionActors.remove(sessionId);
        }
    }

    private boolean legacyTokenAccepted(HttpServletRequest request) {
        String expected = "Bearer " + properties.getToken();
        String actual = request.getHeader("Authorization");
        return actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isActorMode(McpProperties properties) {
        return properties != null && ACTOR_MODE.equalsIgnoreCase(properties.getAuthMode());
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * The identifier that keys the actor binding. On the SSE message endpoint the
     * installed SDK dispatches on the {@code sessionId} query parameter only, so the
     * query parameter is authoritative there; everywhere else (streamable /mcp) the
     * header is, with the query parameter as fallback. Callers reach this method only
     * after the disagreement check, so both identifiers denote the same session when
     * both are present.
     */
    private static String sessionIdFor(HttpServletRequest request, String headerSession, String querySession) {
        if (SSE_MESSAGE_ENDPOINT.equals(request.getRequestURI()) && querySession != null) {
            return querySession;
        }
        return headerSession != null ? headerSession : querySession;
    }

    /** Registers for the two credential modes; auth-mode=none keeps the v1 no-filter default. */
    public static class AuthModeEnabledCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String mode = context.getEnvironment().getProperty("aria.mcp.auth-mode", "none");
            return "token".equalsIgnoreCase(mode) || ACTOR_MODE.equalsIgnoreCase(mode);
        }
    }
}
