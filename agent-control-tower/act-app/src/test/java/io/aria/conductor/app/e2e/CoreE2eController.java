package io.aria.conductor.app.e2e;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.RunExecutionBinding;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.repository.RunExecutionBindingRepository;
import io.aria.conductor.execution.security.ActorAuthenticationFilter;
import io.aria.conductor.execution.security.OperatorSessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Harness-only control surface of the deterministic core E2E distribution
 * (Task 19 wiring of the Task 17 obligations). It exists only when the harness
 * configuration is active ({@code core-e2e} profile, registered by
 * {@link CoreE2eConfiguration}); the production {@code backend-jar} never
 * carries this class at all.
 *
 * <p>The class carries {@code @Profile("core-e2e")} because it is a
 * {@code @RestController} on the test classpath: without the profile gate the
 * application component scan would register it in every other Spring test
 * context (where the harness registry beans do not exist) and break them; with
 * the gate, exactly the harness context serves the two routes and no other
 * context ever sees a harness control bean. The remaining harness classes carry
 * no component annotation and are only registered by the profile-gated
 * {@link CoreE2eConfiguration}.
 *
 * <p>Three operator-only routes, matching the exact contracts the Playwright
 * fixtures assert ({@code e2e/fixtures.ts}):
 * <ul>
 *   <li>{@code POST /api/v1/maintenance/core-e2e/scenario} with
 *       {@code {agentId, scenario}} answers exactly {@code {agentId, scenario}}
 *       for an operator. It records the peer-fixture selection in memory and
 *       controls peers only: no run, approval or binding row is written here.</li>
 *   <li>{@code GET /api/v1/maintenance/core-e2e/worker-token?runId=…} answers
 *       exactly {@code {runId, token}} with the run's worker credential minted
 *       through the committed {@link io.aria.conductor.execution.security.ActorTokenService}
 *       (the same value the harness launcher delivers to the run's peer process);
 *       it drives no database outcome either.</li>
 *   <li>{@code GET /api/v1/maintenance/core-e2e/binding?runId=…} reads one run's
 *       frozen execution binding row back ({@link RunExecutionBinding}): the
 *       frozen half the run was admitted with, its observed runtime state and
 *       the optimistic-lock version, so a spec can assert that the completion it
 *       observed was served by that run's own binding. Read-only, operator-only,
 *       and it never fabricates a row for a run without one (404).</li>
 * </ul>
 *
 * <p>Identity comes from the transport exactly as on the production
 * maintenance surface ({@link MaintenanceController} pattern): the
 * {@code aria.actor} filter attribute, the environment-supplied operator bearer
 * credential, or a validated operator session cookie. A valid worker principal
 * is 403, no identity is 401 -- the harness does not weaken the operator
 * boundary to make a fixture convenient.
 */
@RestController
@Profile("core-e2e")
@RequestMapping("/api/v1/maintenance/core-e2e")
public class CoreE2eController {

    /** Request body of the scenario control: exactly the two asserted fields. */
    public record ScenarioSelection(UUID agentId, String scenario) {
    }

    private final CoreE2eScenarios scenarios;
    private final CoreE2eWorkerTokens workerTokens;
    private final RunRepository runs;
    private final RunExecutionBindingRepository bindings;
    private final OperatorSessionService operatorSessions;

    CoreE2eController(CoreE2eScenarios scenarios, CoreE2eWorkerTokens workerTokens, RunRepository runs,
            RunExecutionBindingRepository bindings, OperatorSessionService operatorSessions) {
        this.scenarios = scenarios;
        this.workerTokens = workerTokens;
        this.runs = runs;
        this.bindings = bindings;
        this.operatorSessions = operatorSessions;
    }

    @PostMapping("/scenario")
    public ResponseEntity<Object> selectScenario(@RequestBody(required = false) ScenarioSelection body,
            HttpServletRequest request) {
        ResponseEntity<Object> rejection = rejectNonOperator(request, true);
        if (rejection != null) {
            return rejection;
        }
        if (body == null || body.agentId() == null || body.scenario() == null || body.scenario().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "agentId and scenario are required"));
        }
        try {
            scenarios.select(body.agentId(), body.scenario());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.ok(Map.of("agentId", body.agentId(), "scenario", body.scenario()));
    }

    @GetMapping("/worker-token")
    public ResponseEntity<Object> workerToken(@RequestParam(name = "runId", required = false) String runId,
            HttpServletRequest request) {
        ResponseEntity<Object> rejection = rejectNonOperator(request, false);
        if (rejection != null) {
            return rejection;
        }
        UUID id;
        try {
            id = UUID.fromString(runId == null ? "" : runId.trim());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "runId must be a UUID"));
        }
        if (runs.findById(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Unknown run: " + id));
        }
        return ResponseEntity.ok(Map.of("runId", id, "token", workerTokens.tokenFor(id)));
    }

    /**
     * The read-only binding route: one run's frozen execution binding row as the
     * backend recorded it. It answers exactly the evidence a governed-run spec
     * asserts -- the frozen half (agent, core, mode, settings, credential
     * reference, configuration revision, deadline) plus the observed runtime
     * state and the row version -- and never invents a row for a run without
     * one. No write path exists here: the binding is written by the run's own
     * coordinator only.
     */
    @GetMapping("/binding")
    public ResponseEntity<Object> executionBinding(@RequestParam(name = "runId", required = false) String runId,
            HttpServletRequest request) {
        ResponseEntity<Object> rejection = rejectNonOperator(request, false);
        if (rejection != null) {
            return rejection;
        }
        UUID id;
        try {
            id = UUID.fromString(runId == null ? "" : runId.trim());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "runId must be a UUID"));
        }
        RunExecutionBinding row = bindings.findById(id).orElse(null);
        if (row == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Run has no execution binding: " + id));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", row.getRunId().toString());
        body.put("agentId", row.getAgentId() == null ? null : row.getAgentId().toString());
        body.put("coreId", row.getCoreId());
        body.put("executionMode", row.getExecutionMode() == null ? null : row.getExecutionMode().name());
        body.put("settingsJson", row.getSettingsJson());
        body.put("credentialRef", row.getCredentialRef());
        body.put("configurationRevision", row.getConfigurationRevision());
        body.put("deadline", row.getDeadline() == null ? null : row.getDeadline().toString());
        body.put("runtimeState", row.getRuntimeState());
        body.put("usageInputTokens", row.getUsageInputTokens());
        body.put("usageOutputTokens", row.getUsageOutputTokens());
        body.put("observedModel", row.getObservedModel());
        body.put("version", row.getVersion());
        return ResponseEntity.ok(body);
    }

    /**
     * Null when the caller holds operator authority; otherwise the exact
     * rejection of the production maintenance surface: 401 for no valid identity
     * and 403 for a valid non-operator principal or a rejected cookie mutation.
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

    private ActorPrincipal operator(HttpServletRequest request, boolean mutation) {
        Object actorAttribute = request.getAttribute(ActorAuthenticationFilter.ACTOR_ATTRIBUTE);
        if (actorAttribute instanceof ActorPrincipal principal) {
            if (principal.isExpired(Instant.now())) {
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
        var cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (var cookie : cookies) {
            if (OperatorSessionService.COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
