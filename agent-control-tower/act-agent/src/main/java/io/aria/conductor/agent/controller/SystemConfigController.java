package io.aria.conductor.agent.controller;

import io.aria.conductor.agent.service.SystemConfigService;
import io.aria.conductor.common.model.SystemConfig;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/v1/system-config")
public class SystemConfigController {

    private final SystemConfigService systemConfigService;

    public SystemConfigController(SystemConfigService systemConfigService) {
        this.systemConfigService = systemConfigService;
    }

    @GetMapping
    public ResponseEntity<List<SystemConfig>> listAll() {
        return ResponseEntity.ok(systemConfigService.listAll());
    }

    @GetMapping("/{key}")
    public ResponseEntity<?> getByKey(@PathVariable String key) {
        try {
            return ResponseEntity.ok(systemConfigService.getByKey(key));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404)
                    .body(Map.of("error", "Unknown system config key: " + key
                            + ". The key must exist before it can be read."));
        }
    }

    @PutMapping("/{key}")
    public ResponseEntity<?> updateValue(@PathVariable String key, @RequestBody Map<String, String> body) {
        try {
            String value = body == null ? null : body.get("value");
            if (value == null || value.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "A value is required for system config key: " + key + "."));
            }
            return ResponseEntity.ok(systemConfigService.updateValue(key, value));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404)
                    .body(Map.of("error", "Unknown system config key: " + key
                            + ". The key must exist before its value can be changed."));
        }
    }

    @PostMapping("/{key}/reset")
    public ResponseEntity<?> resetToDefault(@PathVariable String key) {
        try {
            SystemConfig config = systemConfigService.getByKey(key);
            // The default value is embedded in the description — extract from migration defaults
            // For now, just return current value (actual reset would require storing defaults separately)
            return ResponseEntity.ok(config);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404)
                    .body(Map.of("error", "Unknown system config key: " + key
                            + ". The key must exist before it can be reset."));
        }
    }
}
