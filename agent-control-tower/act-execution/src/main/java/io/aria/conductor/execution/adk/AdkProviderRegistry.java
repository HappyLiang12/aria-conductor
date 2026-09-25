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
 * <p>All providers registered as Spring beans are auto-injected. The supported
 * production cores are exactly {@code opencode} and {@code qoder}; there is no
 * fallback. A configured default that is not one of the registered providers --
 * and an agent selection that is unknown or removed -- is refused with an
 * explicit {@code Unsupported ADK provider} failure instead of being silently
 * substituted.
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

        if (configuredDefault == null || configuredDefault.isBlank()
                || !providers.containsKey(configuredDefault)) {
            // Fail closed: never fall back to another provider. A wrong default
            // would route runs to a core the operator did not select.
            throw new IllegalStateException("Unsupported ADK provider: configured default '"
                    + configuredDefault + "' is not one of the registered providers "
                    + providers.keySet() + "; the supported cores are exactly the registered ones"
                    + " and there is no fallback");
        }
        this.defaultProvider = configuredDefault;

        log.info("ADK providers registered: {} (default: {})",
                providers.keySet(), defaultProvider);
    }

    /**
     * Resolve the provider for the given agent.
     *
     * @param agent the agent to execute
     * @return the resolved provider (never null)
     * @throws IllegalStateException if the selection is unknown or removed
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
            throw new IllegalStateException("Unsupported ADK provider '" + pid + "' for agent "
                    + (agent == null ? "null" : agent.getId()) + "; registered providers: "
                    + providers.keySet() + " (there is no fallback)");
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
