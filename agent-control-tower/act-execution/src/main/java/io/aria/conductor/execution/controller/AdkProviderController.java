package io.aria.conductor.execution.controller;

import io.aria.conductor.execution.adk.AdkProvider;
import io.aria.conductor.execution.adk.AdkProviderRegistry;
import io.aria.conductor.execution.adk.AdkSystemProperties;
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
 * Provider inventory / health API — lets the UI enumerate available ADK backends
 * and probe their health without agent context.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/adk")
public class AdkProviderController {

    private final AdkProviderRegistry providerRegistry;
    private final AdkSystemProperties systemProperties;

    public AdkProviderController(AdkProviderRegistry providerRegistry,
                                 AdkSystemProperties systemProperties) {
        this.providerRegistry = providerRegistry;
        this.systemProperties = systemProperties;
    }

    /**
     * {@code GET /api/v1/adk/providers} — {@code [{ id, displayName, supportsTaskExecution, isDefault }]}.
     */
    @GetMapping("/providers")
    public ResponseEntity<List<Map<String, Object>>> listProviders() {
        String defaultProvider = systemProperties.getDefaultProvider();
        List<Map<String, Object>> providers = providerRegistry.getProviderIds().stream()
                .map(id -> {
                    AdkProvider provider = providerRegistry.getProvider(id);
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", id);
                    entry.put("displayName", displayName(id));
                    entry.put("supportsTaskExecution", provider != null && provider.supportsTaskExecution());
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
     * <p>The controller has no agent context, so health comes from the provider's
     * service-level probe {@link AdkProvider#isServiceHealthy()} (e.g. OpenSandbox
     * server reachability for opencode, ADK host:port for langchain) instead of the
     * instance-scoped {@link AdkProvider#isHealthy(UUID)}. Instance-level probes
     * (sandbox rebuild on repeated failures) remain agent-scoped.
     */
    @GetMapping("/providers/{id}/health")
    public ResponseEntity<Map<String, Object>> getProviderHealth(@PathVariable String id) {
        AdkProvider provider = providerRegistry.getProvider(id);
        if (provider == null) {
            log.warn("ADK provider health probe for unknown provider '{}'", id);
            return ResponseEntity.notFound().build();
        }
        boolean healthy = provider.isServiceHealthy();
        log.info("ADK provider '{}' health probe: {}", id, healthy);
        return ResponseEntity.ok(Map.of(
                "providerId", id,
                "healthy", healthy
        ));
    }

    private String displayName(String id) {
        return switch (id) {
            case "opencode" -> "OpenCode";
            case "langchain" -> "LangChain ADK";
            default -> id.isEmpty() ? id : Character.toUpperCase(id.charAt(0)) + id.substring(1);
        };
    }
}
