package io.aria.conductor.execution.controller;

import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.maintenance.LegacySetupService;
import io.aria.conductor.execution.security.OperatorAuthorityResolver;
import io.aria.conductor.execution.security.OperatorSessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
 * Identity is resolved by the shared {@link OperatorAuthorityResolver} (the
 * {@code aria.actor} attribute, the operator bearer credential or a mutually
 * validated operator session cookie; an anonymous request from loopback is the
 * local single operator); a valid worker credential is rejected with 403
 * before any maintenance work starts, and a caller with no verifiable
 * identity -- absent, invalid or expired, from a non-loopback client -- with
 * 401, the same contract the authentication filter applies.
 */
@RestController
@RequestMapping("/api/v1/maintenance")
public class MaintenanceController {

    private final LegacySetupService setup;
    private final OperatorAuthorityResolver operatorAuthority;

    public MaintenanceController(LegacySetupService setup,
                                 OperatorAuthorityResolver operatorAuthority) {
        this.setup = setup;
        this.operatorAuthority = operatorAuthority;
    }

    /**
     * The operator boundary of the route, resolved once and reused for the
     * work: 403 for a valid worker credential or a failed Origin/CSRF
     * validation of a cookie-authenticated mutation (the resolver's own
     * message), 401 with no verifiable identity -- absent, invalid or expired,
     * the same status the authentication filter answers for an expired
     * credential.
     */
    @PostMapping("/initialize-builtins")
    public ResponseEntity<Object> initializeBuiltins(HttpServletRequest request) {
        ActorPrincipal operator;
        try {
            operator = operatorAuthority.resolveOperator(request, true);
        } catch (OperatorSessionService.ForbiddenMutationException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Operator credential required"));
        }
        return ResponseEntity.ok(setup.initializeMissingBuiltins(operator));
    }
}
