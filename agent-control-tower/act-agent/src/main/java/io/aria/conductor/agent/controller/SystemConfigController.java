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

    /** The run deadline read by the execution runtime; its range is a freezes-into-runs contract. */
    private static final String TASK_DEADLINE_KEY = "aria.tasks.deadline-minutes";
    private static final int TASK_DEADLINE_MIN_MINUTES = 1;
    private static final int TASK_DEADLINE_MAX_MINUTES = 1440;

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
                            + ". The key must exist before it can be read.",
                            "code", "UNKNOWN_SYSTEM_CONFIG_KEY",
                            "surface", "SYSTEM_CONFIG"));
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
            if (TASK_DEADLINE_KEY.equals(key) && !isValidTaskDeadline(value)) {
                // The runtime clamps an out-of-range value silently, so accepting it
                // here would show the operator a deadline that is never applied.
                return ResponseEntity.badRequest().body(Map.of(
                        "error", TASK_DEADLINE_KEY + " must be a whole number of minutes between "
                                + TASK_DEADLINE_MIN_MINUTES + " and " + TASK_DEADLINE_MAX_MINUTES
                                + " (received: \"" + value + "\"). Set it in Configure > System Config > Execution.",
                        "code", "INVALID_SYSTEM_CONFIG_VALUE",
                        "surface", "SYSTEM_CONFIG"));
            }
            return ResponseEntity.ok(systemConfigService.updateValue(key, value));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404)
                    .body(Map.of("error", "Unknown system config key: " + key
                            + ". The key must exist before its value can be changed.",
                            "code", "UNKNOWN_SYSTEM_CONFIG_KEY",
                            "surface", "SYSTEM_CONFIG"));
        }
    }

    private static boolean isValidTaskDeadline(String value) {
        try {
            int minutes = Integer.parseInt(value.trim());
            return minutes >= TASK_DEADLINE_MIN_MINUTES && minutes <= TASK_DEADLINE_MAX_MINUTES;
        } catch (NumberFormatException e) {
            return false;
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
                            + ". The key must exist before it can be reset.",
                            "code", "UNKNOWN_SYSTEM_CONFIG_KEY",
                            "surface", "SYSTEM_CONFIG"));
        }
    }
}
