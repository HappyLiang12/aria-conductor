package io.aria.conductor.execution.controller;

import io.aria.conductor.execution.credential.PackCredentialService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Pack-credential handoff endpoint. Retained after the legacy tool-execution
 * retirement because the git handoff still resolves its token from the git
 * pack's stored credentials; pack listing/registration/approval routes are gone
 * (the run-owned cores own tool execution).
 */
@RestController
@RequestMapping("/api/v1/packs")
@RequiredArgsConstructor
public class ToolPackController {

    private final PackCredentialService credentialService;

    @PostMapping("/{id}/credentials")
    public ResponseEntity<Map<String, Object>> storeCredential(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        String credKey = body.get("key");
        String value = body.get("value");
        String agentId = body.get("agentId"); // nullable = global
        if (credKey == null || value == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "key and value are required"));
        }
        credentialService.store(id, agentId, credKey, value);
        // F8: credentials are resolved per request (GitTokenSource), so a stored
        // value takes effect on the next operation; no restart is required.
        return ResponseEntity.ok(Map.of(
                "status", "stored",
                "key", credKey,
                "restartRequired", false));
    }
}
