package io.aria.conductor.execution.security;

import io.aria.conductor.common.security.ActorPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Operator REST/UI authentication boundary: resolves the caller's
 * {@link ActorPrincipal} from the operator bearer credential or the operator
 * session cookie and exposes it as the {@code aria.actor} request attribute.
 *
 * <p>Status contract: 401 when no valid identity is presented (absent, invalid,
 * expired or revoked); 403 when a valid worker credential attempts an
 * operator-only route, or when a cookie-authenticated mutation fails
 * Origin/CSRF validation.
 *
 * <p>Production registration is part of the cutover task (Task 18) so the
 * existing REST surface keeps its current behaviour until the operator
 * lifecycle is wired; this class is deliberately not a scanned bean and has no
 * unauthenticated bypass switch.
 */
public class ActorAuthenticationFilter extends OncePerRequestFilter {

    public static final String ACTOR_ATTRIBUTE = "aria.actor";

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final ActorTokenService actorTokens;
    private final OperatorSessionService operatorSessions;
    private final List<String> protectedPathPrefixes;

    public ActorAuthenticationFilter(ActorTokenService actorTokens, OperatorSessionService operatorSessions,
                                     List<String> protectedPathPrefixes) {
        this.actorTokens = actorTokens;
        this.operatorSessions = operatorSessions;
        this.protectedPathPrefixes = List.copyOf(protectedPathPrefixes);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isProtected(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);

        // 1. operator bearer credential (header-authenticated, no CSRF surface)
        if (operatorSessions.verifyOperatorCredential(authorization)) {
            request.setAttribute(ACTOR_ATTRIBUTE, ActorPrincipal.operator(null));
            chain.doFilter(request, response);
            return;
        }

        // 2. operator session cookie; cookie-authenticated mutations validate Origin/CSRF
        String sessionId = sessionCookie(request);
        ActorPrincipal operator = operatorSessions.authenticateSession(sessionId).orElse(null);
        if (operator != null) {
            if (!SAFE_METHODS.contains(request.getMethod())) {
                try {
                    operatorSessions.validateMutation(
                            operatorSessions.findSession(sessionId).orElseThrow(),
                            request.getHeader(OperatorSessionService.CSRF_HEADER),
                            request.getHeader(HttpHeaders.ORIGIN));
                } catch (OperatorSessionService.ForbiddenMutationException e) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    return;
                }
            }
            request.setAttribute(ACTOR_ATTRIBUTE, operator);
            chain.doFilter(request, response);
            return;
        }

        // 3. a valid worker credential is authenticated but never operator-authorized
        if (actorTokens.resolveBearer(authorization).isPresent()) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    private boolean isProtected(String uri) {
        return protectedPathPrefixes.stream().anyMatch(uri::startsWith);
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
