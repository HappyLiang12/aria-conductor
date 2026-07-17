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
    public ResponseEntity<SystemConfig> getByKey(@PathVariable String key) {
        try {
            return ResponseEntity.ok(systemConfigService.getByKey(key));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/{key}")
    public ResponseEntity<SystemConfig> updateValue(@PathVariable String key, @RequestBody Map<String, String> body) {
        try {
            String value = body.get("value");
            if (value == null || value.isBlank()) {
                return ResponseEntity.badRequest().build();
            }
            return ResponseEntity.ok(systemConfigService.updateValue(key, value));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{key}/reset")
    public ResponseEntity<SystemConfig> resetToDefault(@PathVariable String key) {
        try {
            SystemConfig config = systemConfigService.getByKey(key);
            // The default value is embedded in the description — extract from migration defaults
            // For now, just return current value (actual reset would require storing defaults separately)
            return ResponseEntity.ok(config);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
