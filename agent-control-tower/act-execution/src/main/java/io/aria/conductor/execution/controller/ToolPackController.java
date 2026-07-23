package io.aria.conductor.execution.controller;

import io.aria.conductor.common.model.ToolPack;
import io.aria.conductor.common.model.VersionStatus;
import io.aria.conductor.common.repository.ToolPackRepository;
import io.aria.conductor.execution.credential.PackCredentialService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Minimal pack-management endpoints (Layer A governance).
 * Register packs/tools as PENDING; approve -> APPROVED.
 */
@RestController
@RequestMapping("/api/v1/packs")
@RequiredArgsConstructor
public class ToolPackController {

    private final ToolPackRepository packRepository;
    private final PackCredentialService credentialService;

    @GetMapping
    public List<ToolPack> listPacks() {
        return packRepository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ToolPack> getPack(@PathVariable String id) {
        return packRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ToolPack registerPack(@RequestBody ToolPack pack) {
        if (pack.getId() == null || pack.getId().isBlank()) {
            pack.setId(java.util.UUID.randomUUID().toString());
        }
        if (pack.getStatus() == null) {
            pack.setStatus(VersionStatus.PENDING);
        }
        return packRepository.save(pack);
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<ToolPack> approvePack(@PathVariable String id) {
        return packRepository.findById(id)
                .map(pack -> {
                    pack.setStatus(VersionStatus.APPROVED);
                    pack.setEnabled(true);
                    return ResponseEntity.ok(packRepository.save(pack));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<ToolPack> rejectPack(@PathVariable String id) {
        return packRepository.findById(id)
                .map(pack -> {
                    pack.setStatus(VersionStatus.REJECTED);
                    pack.setEnabled(false);
                    return ResponseEntity.ok(packRepository.save(pack));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/credentials")
    public ResponseEntity<Map<String, String>> storeCredential(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        String credKey = body.get("key");
        String value = body.get("value");
        String agentId = body.get("agentId"); // nullable = global
        if (credKey == null || value == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "key and value are required"));
        }
        credentialService.store(id, agentId, credKey, value);
        return ResponseEntity.ok(Map.of("status", "stored", "key", credKey));
    }
}
