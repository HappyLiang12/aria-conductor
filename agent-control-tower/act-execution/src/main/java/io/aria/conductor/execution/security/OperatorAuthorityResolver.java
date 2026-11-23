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
        // Checked here first, exactly as before, so a bearer outranks a session
        // cookie; authorizeTransport re-checks it (constant time) because it is
        // the one transport-neutral rule.
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
        // Only the credential/loopback tail is left. The mutation Origin rule
        // guards exactly the loopback branch, which is the only branch that can
        // still return here.
        ActorPrincipal principal = authorizeTransport(authorization, clientAddress(request));
        if (mutation) {
            // Loopback auto-authority for a MUTATION must still refuse a
            // browser-driven cross-site request: every browser POST carries an
            // Origin header, while local tools (curl, SDKs, the CLI) send none.
            // A cross-site page can fire a "simple" POST that CORS cannot block
            // and whose response it cannot read -- so the response being unread
            // is no protection. An Origin that is present but not ours means the
            // request was forged by a web page the operator had open: refuse it.
            // Authenticated identities above are already validated (session
            // mutations re-check CSRF/Origin; bearer holders are not web pages).
            operatorSessions.rejectCrossSiteOrigin(request.getHeader(HttpHeaders.ORIGIN));
        }
        return principal;
    }

    /**
     * The same operator decision without a servlet request, for transports that
     * do not have one (the aria live WebSocket upgrade). Precedence is the one
     * documented on the class: operator credential, then a worker credential is
     * 403 (never promoted, not even from loopback), then loopback is the local
     * operator, then 401.
     *
     * @param authorization the raw {@code Authorization} header value, may be null
     * @param remoteAddress the already-resolved client address (trusted-proxy aware)
     * @throws OperatorSessionService.ForbiddenMutationException worker credential (403)
     * @throws SecurityException nothing verifiable and not loopback (401)
     */
    public ActorPrincipal authorizeTransport(String authorization, String remoteAddress) {
        if (operatorSessions.verifyOperatorCredential(authorization)) {
            return ActorPrincipal.operator(null);
        }
        if (actorTokens.resolveBearer(authorization).isPresent()) {
            throw new OperatorSessionService.ForbiddenMutationException("Operator authority required");
        }
        if (isLoopback(remoteAddress)) {
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
        return clientAddress(request.getRemoteAddr(), request.getHeader("X-Forwarded-For"));
    }

    /**
     * The trusted-proxy rule without a servlet request, so a WebSocket upgrade
     * derives the client address the same way an HTTP call does.
     */
    public String clientAddress(String peer, String forwardedFor) {
        if (peer != null && trustedProxies.contains(peer)) {
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                int comma = forwardedFor.lastIndexOf(',');
                String client = (comma >= 0 ? forwardedFor.substring(comma + 1) : forwardedFor).trim();
                if (!client.isEmpty()) {
                    return client;
                }
            }
        }
        return peer;
    }

    private boolean isLoopback(String address) {
        return "127.0.0.1".equals(address) || "::1".equals(address)
                || "0:0:0:0:0:0:0:1".equals(address);
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
