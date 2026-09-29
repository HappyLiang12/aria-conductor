package io.aria.conductor.execution.controller;

import io.aria.conductor.execution.security.OperatorSessionService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Operator session boundary (plan §2.2):
 * <ul>
 *   <li>{@code POST /api/v1/operator/session} — authenticate the separately
 *       configured operator bearer credential; issue an HttpOnly/SameSite
 *       session cookie and a CSRF token. 401 without it; never 200 for a
 *       worker token or an unconfigured credential.</li>
 *   <li>{@code DELETE /api/v1/operator/session} — revoke that session only
 *       (core credentials are untouched). 401 without a valid session, 403 on
 *       failed Origin/CSRF validation.</li>
 * </ul>
 * The operator credential is only ever read from the request header: it is
 * never shipped in frontend assets, URLs or worker environments.
 */
@RestController
@RequestMapping("/api/v1/operator")
public class OperatorSessionController {

    private final OperatorSessionService operatorSessions;

    public OperatorSessionController(OperatorSessionService operatorSessions) {
        this.operatorSessions = operatorSessions;
    }

    @PostMapping("/session")
    public ResponseEntity<Map<String, Object>> createSession(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletResponse response) {
        if (!operatorSessions.verifyOperatorCredential(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Operator credential required"));
        }
        OperatorSessionService.OperatorSession session = operatorSessions.createSession();
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(session).toString());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("csrfToken", session.csrfToken());
        body.put("expiresAt", session.expiresAt().toString());
        return ResponseEntity.ok(body);
    }

    @DeleteMapping("/session")
    public ResponseEntity<Map<String, Object>> revokeSession(
            @CookieValue(value = OperatorSessionService.COOKIE_NAME, required = false) String sessionId,
            @RequestHeader(value = OperatorSessionService.CSRF_HEADER, required = false) String csrfToken,
            @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin,
            HttpServletResponse response) {
        OperatorSessionService.OperatorSession session = operatorSessions.findSession(sessionId).orElse(null);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Operator session required"));
        }
        try {
            operatorSessions.validateMutation(session, csrfToken, origin);
        } catch (OperatorSessionService.ForbiddenMutationException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        }
        operatorSessions.revokeSession(sessionId);
        response.addHeader(HttpHeaders.SET_COOKIE, clearedCookie().toString());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    private ResponseCookie sessionCookie(OperatorSessionService.OperatorSession session) {
        return ResponseCookie.from(OperatorSessionService.COOKIE_NAME, session.sessionId())
                .httpOnly(true)
                .sameSite("Strict")
                .path("/")
                .secure(operatorSessions.isCookieSecure())
                .maxAge(operatorSessions.sessionTtl())
                .build();
    }

    private ResponseCookie clearedCookie() {
        return ResponseCookie.from(OperatorSessionService.COOKIE_NAME, "")
                .httpOnly(true)
                .sameSite("Strict")
                .path("/")
                .secure(operatorSessions.isCookieSecure())
                .maxAge(0)
                .build();
    }
}
