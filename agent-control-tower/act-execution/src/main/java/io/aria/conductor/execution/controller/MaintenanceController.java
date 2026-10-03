package io.aria.conductor.execution.controller;

import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.maintenance.LegacySetupService;
import io.aria.conductor.execution.security.ActorAuthenticationFilter;
import io.aria.conductor.execution.security.OperatorSessionService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Operator-only maintenance surface (plan section 2.2):
 *
 * <ul>
 *   <li>{@code POST /api/v1/maintenance/initialize-builtins} — explicit setup;
 *       idempotent and create-only.</li>
 * </ul>
 *
 * <p>There is no startup hook, scheduler or health-path trigger for this
 * route: it executes only for an authenticated operator that calls it.
 * Identity comes from the transport (the {@code aria.actor} attribute, the
 * operator bearer credential or a mutually validated operator session cookie);
 * a valid worker principal is rejected with 403 before any maintenance work
 * starts, and a caller with no valid identity -- absent, invalid or expired --
 * with 401, the same contract the authentication filter applies.
 */
@RestController
@RequestMapping("/api/v1/maintenance")
public class MaintenanceController {

    private final LegacySetupService setup;
    private final OperatorSessionService operatorSessions;

    public MaintenanceController(LegacySetupService setup,
                                 OperatorSessionService operatorSessions) {
        this.setup = setup;
        this.operatorSessions = operatorSessions;
    }

    @PostMapping("/initialize-builtins")
    public ResponseEntity<Object> initializeBuiltins(HttpServletRequest request) {
        ResponseEntity<Object> rejection = rejectNonOperator(request, true);
        if (rejection != null) {
            return rejection;
        }
        return ResponseEntity.ok(setup.initializeMissingBuiltins(ActorPrincipal.operator(null)));
    }

    /**
     * Null when the caller holds operator authority; otherwise the exact
     * rejection: 401 with no valid identity -- absent, invalid or expired, the
     * same status the authentication filter answers for an expired credential --
     * and 403 for a valid worker/non-operator principal or a failed Origin/CSRF
     * validation of a cookie-authenticated mutation.
     */
    private ResponseEntity<Object> rejectNonOperator(HttpServletRequest request, boolean mutation) {
        ActorPrincipal actor;
        try {
            actor = operator(request, mutation);
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Operator authority required"));
        }
        return actor == null
                ? ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Operator credential required"))
                : null;
    }

    /** Resolves the operator principal, or {@code null} when no valid identity is presented. */
    private ActorPrincipal operator(HttpServletRequest request, boolean mutation) {
        Object actorAttribute = request.getAttribute(ActorAuthenticationFilter.ACTOR_ATTRIBUTE);
        if (actorAttribute instanceof ActorPrincipal principal) {
            if (principal.isExpired(Instant.now())) {
                // An expired credential is no valid identity: the filter contract
                // answers 401, so the attribute path must not upgrade it to 403.
                return null;
            }
            principal.requireOperator();
            return principal;
        }
        if (operatorSessions.verifyOperatorCredential(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            return ActorPrincipal.operator(null);
        }
        String sessionId = sessionCookie(request);
        OperatorSessionService.OperatorSession session = operatorSessions.findSession(sessionId).orElse(null);
        if (session == null) {
            return null;
        }
        if (mutation) {
            operatorSessions.validateMutation(session, request.getHeader(OperatorSessionService.CSRF_HEADER),
                    request.getHeader(HttpHeaders.ORIGIN));
        }
        return ActorPrincipal.operator(session.expiresAt());
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
