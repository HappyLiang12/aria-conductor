package io.aria.conductor.execution.controller;

import io.aria.conductor.execution.adk.AdkSystemProperties;
import io.aria.conductor.execution.runtime.CoreAdapters;
import io.aria.conductor.execution.runtime.CoreCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Core inventory / health API — lets the UI enumerate the production cores and
 * probe their service-level health without agent context.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/adk")
public class AdkProviderController {

    private final AdkSystemProperties systemProperties;
    private final CoreCatalog coreCatalog;
    private final CoreAdapters adapterRegistry;

    public AdkProviderController(AdkSystemProperties systemProperties,
                                 CoreCatalog coreCatalog,
                                 CoreAdapters adapterRegistry) {
        this.systemProperties = systemProperties;
        this.coreCatalog = coreCatalog;
        this.adapterRegistry = adapterRegistry;
    }

    /**
     * {@code GET /api/v1/adk/providers} — {@code [{ id, displayName, supportsTaskExecution, isDefault }]}.
     *
     * <p>Task 18 cutover: the inventory is the production core catalog (exactly
     * {@code opencode} and {@code qoder}) — the cores admission accepts — and never
     * a provider bean list. The configured default is the documented default core.
     */
    @GetMapping("/providers")
    public ResponseEntity<List<Map<String, Object>>> listProviders() {
        String defaultProvider = systemProperties.getDefaultProvider();
        List<Map<String, Object>> providers = coreCatalog.coreIds().stream()
                .map(id -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", id);
                    entry.put("displayName", displayName(id));
                    entry.put("supportsTaskExecution", adapterRegistry != null
                            && adapterRegistry.supports(id));
                    entry.put("isDefault", id.equals(defaultProvider));
                    return entry;
                })
                .toList();
        log.info("ADK providers listed: {} (default: {})", providers.size(), defaultProvider);
        return ResponseEntity.ok(providers);
    }

    /**
     * {@code GET /api/v1/adk/providers/{id}/health} — {@code { providerId, healthy }}.
     *
     * <p>The answer is the registered core's service-level descriptor. A core
     * outside the production catalog is a 404, never a substituted provider.
     */
    @GetMapping("/providers/{id}/health")
    public ResponseEntity<Map<String, Object>> getProviderHealth(@PathVariable String id) {
        boolean healthy;
        if (coreCatalog.coreIds().contains(id)
                && adapterRegistry != null && adapterRegistry.supports(id)) {
            healthy = adapterRegistry.require(id).serviceHealthy();
        } else {
            log.warn("Core health probe for unknown core '{}'", id);
            return ResponseEntity.notFound().build();
        }
        log.info("Core '{}' health probe: {}", id, healthy);
        return ResponseEntity.ok(Map.of(
                "providerId", id,
                "healthy", healthy
        ));
    }

    private String displayName(String id) {
        return switch (id) {
            case "opencode" -> "OpenCode";
            case "qoder" -> "Qoder";
            default -> id.isEmpty() ? id : Character.toUpperCase(id.charAt(0)) + id.substring(1);
        };
    }
}
