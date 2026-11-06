package io.aria.conductor.execution.housekeeping;

import io.aria.conductor.execution.housekeeping.HousekeepingModel.Exclusions;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.HousekeepingReceipt;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.HousekeepingRequest;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.ScanResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Housekeeping S4: operator cleanup endpoints.
 *
 * <p>{@code GET /scan} is strictly read-only; {@code POST /execute} requires an
 * explicit {@code confirm:true} flag (the UI confirm modal is the human gate —
 * the Aria path additionally goes through the ApprovalGate inside its tool
 * handler).
 */
@RestController
@RequestMapping("/api/v1/housekeeping")
public class HousekeepingController {

    private final HousekeepingService housekeepingService;

    public HousekeepingController(HousekeepingService housekeepingService) {
        this.housekeepingService = housekeepingService;
    }

    @GetMapping("/scan")
    public ResponseEntity<ScanResult> scan(
            @RequestParam(required = false, defaultValue = "false") boolean includeStuck) {
        return ResponseEntity.ok(housekeepingService.scan(includeStuck, Exclusions.empty()));
    }

    @PostMapping("/execute")
    public ResponseEntity<HousekeepingReceipt> execute(@RequestBody HousekeepingRequest request) {
        // Controller-level gate (400 before any service interaction); the service
        // re-checks as defense in depth.
        if (request == null || !request.confirm()
                || request.categories() == null || request.categories().isEmpty()) {
            throw new IllegalArgumentException(
                    "Housekeeping execute requires confirm=true and at least one category");
        }
        return ResponseEntity.ok(housekeepingService.execute(request));
    }
}
