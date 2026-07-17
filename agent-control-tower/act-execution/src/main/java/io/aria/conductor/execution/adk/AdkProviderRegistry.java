package io.aria.conductor.execution.adk;

import io.aria.conductor.common.model.Agent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Registry that routes agent execution to the correct {@link AdkProvider}
 * based on {@link Agent#getAdkProvider()}.
 *
 * <p>All providers registered as Spring beans are auto-injected. The
 * {@link #resolve(Agent)} method falls back to the configured default
 * provider when the agent does not specify one.
 */
@Slf4j
@Component
public class AdkProviderRegistry {

    private final Map<String, AdkProvider> providers;
    private final String defaultProvider;

    public AdkProviderRegistry(List<AdkProvider> providerList,
                               AdkSystemProperties systemProperties) {
        // Use LinkedHashMap to preserve insertion order from the list
        this.providers = providerList.stream()
                .collect(Collectors.toUnmodifiableMap(
                        AdkProvider::providerId, Function.identity()));

        if (providers.isEmpty()) {
            throw new IllegalStateException(
                    "No ADK providers registered. Please add at least one AdkProvider bean.");
        }

        String configuredDefault = systemProperties != null
                ? systemProperties.getDefaultProvider() : null;

        if (configuredDefault != null && providers.containsKey(configuredDefault)) {
            this.defaultProvider = configuredDefault;
        } else {
            // Use the first provider from the list for deterministic ordering
            String fallback = providerList.get(0).providerId();
            if (configuredDefault == null || configuredDefault.isBlank()) {
                log.warn("No default ADK provider configured; falling back to first available provider '{}'", fallback);
            } else {
                log.warn("Configured default ADK provider '{}' not found; falling back to '{}'. Available: {}",
                        configuredDefault, fallback, providers.keySet());
            }
            this.defaultProvider = fallback;
        }

        log.info("ADK providers registered: {} (default: {})",
                providers.keySet(), defaultProvider);
    }

    /**
     * Resolve the provider for the given agent.
     *
     * @param agent the agent to execute
     * @return the resolved provider (never null)
     * @throws IllegalStateException if no matching provider exists
     */
    public AdkProvider resolve(Agent agent) {
        if (providers.isEmpty()) {
            throw new IllegalStateException("No ADK providers available to resolve");
        }
        String pid = agent != null ? agent.getAdkProvider() : null;
        if (pid == null || pid.isBlank()) {
            pid = defaultProvider;
        }

        AdkProvider provider = providers.get(pid);
        if (provider == null) {
            log.warn("Unknown ADK provider '{}' for agent {} — falling back to default '{}'",
                    pid, agent == null ? "null" : agent.getId(), defaultProvider);
            provider = providers.get(defaultProvider);
        }
        return provider;
    }

    /** All registered provider IDs. */
    public List<String> getProviderIds() {
        return List.copyOf(providers.keySet());
    }

    /** Look up a provider by ID (primarily for health-check / management endpoints). */
    public AdkProvider getProvider(String providerId) {
        return providers.get(providerId);
    }
}
