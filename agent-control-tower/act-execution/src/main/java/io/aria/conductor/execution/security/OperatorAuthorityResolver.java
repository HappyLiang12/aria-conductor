package io.aria.conductor.execution.security;

import io.aria.conductor.common.security.ActorPrincipal;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Single authority resolution for every operator-gated route (2026-10-05 local
 * authority simplification; amends the agent-core spec §6.2).
 *
 * <p>Precedence, fixed: an explicit operator bearer credential is operator;
 * an operator session cookie is operator (mutations re-validate CSRF/Origin);
 * an explicit worker/run-scoped credential is authenticated but NEVER promoted
 * to operator -- not even from loopback, so forwarded MCP worker calls keep
 * their restriction; an anonymous request from loopback is the local operator
 * (single-operator localhost deployment); everything else is 401. An invalid
 * or expired token counts as "no identity presented" and falls through to the
 * loopback/401 rules instead of 403.
 */
@Service
public class OperatorAuthorityResolver {

    private final OperatorSessionService operatorSessions;
    private final ActorTokenService actorTokens;
    private final Set<String> trustedProxies;

    public OperatorAuthorityResolver(OperatorSessionService operatorSessions,
            ActorTokenService actorTokens,
            @Value("${aria.operator.trusted-proxies:}") String trustedProxies) {
        this.operatorSessions = operatorSessions;
        this.actorTokens = actorTokens;
        Set<String> proxies = new LinkedHashSet<>();
        if (trustedProxies != null) {
            Arrays.stream(trustedProxies.split(",")).map(String::trim)
                    .filter(entry -> !entry.isEmpty()).forEach(proxies::add);
        }
        this.trustedProxies = Set.copyOf(proxies);
    }

    /**
     * Resolves the caller's operator authority or throws: 403
     * ({@link OperatorSessionService.ForbiddenMutationException}) for a worker
     * credential or a failed cookie-mutation validation, 401
     * ({@link SecurityException}) when nothing verifiable is presented.
     */
    public ActorPrincipal resolveOperator(HttpServletRequest request, boolean mutation) {
        Object attribute = request.getAttribute(ActorAuthenticationFilter.ACTOR_ATTRIBUTE);
        if (attribute instanceof ActorPrincipal principal) {
            principal.requireActive(Instant.now());
            principal.requireOperator();
            return principal;
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (operatorSessions.verifyOperatorCredential(authorization)) {
            return ActorPrincipal.operator(null);
        }
        String sessionId = sessionCookie(request);
        OperatorSessionService.OperatorSession session =
                operatorSessions.findSession(sessionId).orElse(null);
        if (session != null) {
            if (mutation) {
                operatorSessions.validateMutation(session,
                        request.getHeader(OperatorSessionService.CSRF_HEADER),
                        request.getHeader(HttpHeaders.ORIGIN));
            }
            return ActorPrincipal.operator(session.expiresAt());
        }
        if (actorTokens.resolveBearer(authorization).isPresent()) {
            throw new OperatorSessionService.ForbiddenMutationException("Operator authority required");
        }
        if (isLoopbackClient(request)) {
            return ActorPrincipal.operator(null);
        }
        throw new SecurityException("Operator session required");
    }

    /**
     * The client address this request really came from: the direct peer, unless
     * the peer is an explicitly trusted proxy, in which case the rightmost
     * X-Forwarded-For entry (added by that proxy) is the client. XFF from any
     * other peer is attacker-controlled and ignored.
     */
    public String clientAddress(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (peer != null && trustedProxies.contains(peer)) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.lastIndexOf(',');
                String client = (comma >= 0 ? forwarded.substring(comma + 1) : forwarded).trim();
                if (!client.isEmpty()) {
                    return client;
                }
            }
        }
        return peer;
    }

    private boolean isLoopbackClient(HttpServletRequest request) {
        String client = clientAddress(request);
        return "127.0.0.1".equals(client) || "::1".equals(client)
                || "0:0:0:0:0:0:0:1".equals(client);
    }

    private static String sessionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (OperatorSessionService.COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
