package io.aria.conductor.execution.housekeeping;

import io.aria.conductor.execution.housekeeping.HousekeepingModel.Exclusions;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.HousekeepingReceipt;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.HousekeepingRequest;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.ScanResult;
import io.aria.conductor.execution.security.OperatorAuthorityResolver;
import io.aria.conductor.execution.security.OperatorSessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Housekeeping S4: operator cleanup endpoints.
 *
 * <p>{@code GET /scan} is strictly read-only; {@code POST /execute} is
 * operator-only and also requires an explicit {@code confirm:true} flag (the UI
 * confirm modal is the human gate — the Aria path additionally goes through the
 * ApprovalGate inside its tool handler). The {@code confirm} flag is
 * caller-supplied, so it can never be authority: the operator check runs first
 * and the body check second.
 */
@RestController
@RequestMapping("/api/v1/housekeeping")
public class HousekeepingController {

    private static final String EXECUTE_REFUSAL = "Operator authority required: executing"
            + " housekeeping is an operator decision. Run it from the Housekeeping panel"
            + " on the Ops page.";

    private final HousekeepingService housekeepingService;
    private final OperatorAuthorityResolver operatorAuthority;

    public HousekeepingController(HousekeepingService housekeepingService,
                                  OperatorAuthorityResolver operatorAuthority) {
        this.housekeepingService = housekeepingService;
        this.operatorAuthority = operatorAuthority;
    }

    @GetMapping("/scan")
    public ResponseEntity<ScanResult> scan(
            @RequestParam(required = false, defaultValue = "false") boolean includeStuck) {
        return ResponseEntity.ok(housekeepingService.scan(includeStuck, Exclusions.empty()));
    }

    @PostMapping("/execute")
    public ResponseEntity<?> execute(@RequestBody HousekeepingRequest request,
                                     HttpServletRequest httpRequest) {
        // Authority first: confirm is an argument the caller controls, so it can never
        // authorise the call (T11 M2). A worker that sends confirm:true is still refused.
        ResponseEntity<Object> refusal = rejectNonOperator(httpRequest);
        if (refusal != null) {
            return refusal;
        }
        // Body gate (400 before any service interaction); the service
        // re-checks as defense in depth.
        if (request == null || !request.confirm()
                || request.categories() == null || request.categories().isEmpty()) {
            throw new IllegalArgumentException(
                    "Housekeeping execute requires confirm=true and at least one category");
        }
        return ResponseEntity.ok(housekeepingService.execute(request));
    }

    /**
     * The same authority decision the other operator-gated controllers make: 403 for
     * a worker credential (or a forged cookie mutation), 401 with no verifiable
     * identity. Null means the caller is the operator. The refusal names the action
     * and the surface where the operator can act, in the MCP housekeeping_execute
     * voice.
     */
    private ResponseEntity<Object> rejectNonOperator(HttpServletRequest request) {
        try {
            operatorAuthority.resolveOperator(request, true);
        } catch (OperatorSessionService.ForbiddenMutationException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", EXECUTE_REFUSAL));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", EXECUTE_REFUSAL));
        }
        return null;
    }
}
