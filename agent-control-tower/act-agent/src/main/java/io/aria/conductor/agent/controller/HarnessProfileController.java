package io.aria.conductor.agent.controller;

import io.aria.conductor.agent.service.HarnessProfileService;
import io.aria.conductor.common.model.HarnessProfile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only API over the reusable harness profiles (stored in system_config). Lets the dashboard
 * and E2E tests inspect the effective, resolved configuration that tunes the agent loop.
 */
@RestController
@RequestMapping("/api/v1/harness-profiles")
public class HarnessProfileController {

    private final HarnessProfileService harnessProfileService;

    public HarnessProfileController(HarnessProfileService harnessProfileService) {
        this.harnessProfileService = harnessProfileService;
    }

    @GetMapping
    public ResponseEntity<List<HarnessProfile>> list() {
        return ResponseEntity.ok(harnessProfileService.listProfiles());
    }

    @GetMapping("/{name}")
    public ResponseEntity<HarnessProfile> getByName(@PathVariable String name) {
        return ResponseEntity.ok(harnessProfileService.resolveByName(name));
    }
}
