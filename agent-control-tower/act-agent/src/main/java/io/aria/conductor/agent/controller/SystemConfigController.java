package io.aria.conductor.agent.controller;

import io.aria.conductor.agent.service.SystemConfigService;
import io.aria.conductor.common.config.SystemConfigKeys;
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

    /**
     * Seed defaults of the keys the dashboard can reset. They mirror the seed
     * migrations: V14 (LLM), V17 (report) and V73 (run deadline).
     */
    private static final Map<String, String> DEFAULTS = Map.of(
            "llm.request.timeout.seconds", "600",
            "aria.sse.timeout.ms", "600000",
            "report.generate.max.tokens", "16384",
            "report.amend.max.tokens", "16384",
            SystemConfigKeys.TASK_DEADLINE_MINUTES,
            String.valueOf(SystemConfigKeys.TASK_DEADLINE_DEFAULT_MINUTES));

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
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "A value is required for system config key: " + key + ".",
                        "code", "INVALID_SYSTEM_CONFIG_VALUE",
                        "surface", "SYSTEM_CONFIG"));
            }
            if (SystemConfigKeys.TASK_DEADLINE_MINUTES.equals(key) && !isValidTaskDeadline(value)) {
                // The runtime clamps an out-of-range value silently, so accepting it
                // here would show the operator a deadline that is never applied.
                return ResponseEntity.badRequest().body(Map.of(
                        "error", SystemConfigKeys.TASK_DEADLINE_MINUTES
                                + " must be a whole number of minutes between "
                                + SystemConfigKeys.TASK_DEADLINE_MIN_MINUTES + " and "
                                + SystemConfigKeys.TASK_DEADLINE_MAX_MINUTES
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
            return minutes >= SystemConfigKeys.TASK_DEADLINE_MIN_MINUTES
                    && minutes <= SystemConfigKeys.TASK_DEADLINE_MAX_MINUTES;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @PostMapping("/{key}/reset")
    public ResponseEntity<?> resetToDefault(@PathVariable String key) {
        try {
            systemConfigService.getByKey(key); // unknown keys keep their 404
            String defaultValue = DEFAULTS.get(key);
            if (defaultValue == null) {
                // Never answer a reset with the unchanged value: a success toast
                // for a write that did not happen is worse than an honest refusal.
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "No default is recorded for system config key: " + key
                                + ". Set the value explicitly instead.",
                        "code", "NO_SYSTEM_CONFIG_DEFAULT",
                        "surface", "SYSTEM_CONFIG"));
            }
            return ResponseEntity.ok(systemConfigService.updateValue(key, defaultValue));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404)
                    .body(Map.of("error", "Unknown system config key: " + key
                            + ". The key must exist before it can be reset.",
                            "code", "UNKNOWN_SYSTEM_CONFIG_KEY",
                            "surface", "SYSTEM_CONFIG"));
        }
    }
}
