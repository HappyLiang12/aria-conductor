package io.aria.conductor.execution.adk;

import io.aria.conductor.common.model.Agent;
import io.aria.conductor.execution.runtime.CoreCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Registry that routes agent execution to the correct {@link AdkProvider}
 * based on {@link Agent#getAdkProvider()}.
 *
 * <p>All providers registered as Spring beans are auto-injected. The supported
 * production cores are exactly the two registered ones: {@code opencode} (served
 * by its provider bean) and {@code qoder} (served by its run-owned core runtime,
 * without a provider bean). There is no fallback. A configured default or agent
 * selection that is neither a registered provider nor a core of the production
 * {@link CoreCatalog} -- unknown or removed -- is refused with an explicit
 * {@code Unsupported ADK provider} failure instead of being silently substituted.
 *
 * <p><strong>Catalog cores.</strong> The run path of a core in the production
 * catalog is the run coordinator through the core launcher
 * ({@code CoreRunLauncher} / {@code AgentLoopEngine}): the legacy provider
 * resolution is only reached for a non-catalog core. A catalog core therefore
 * needs no provider bean here. The configured default is accepted when the
 * catalog supports it, and {@link #resolve(Agent)} answers {@code null} for a
 * catalog core instead of demanding a provider bean that by design does not
 * exist -- the caller that owns the run-owned core path supplies the runtime.
 * This also keeps the provider-level consumers truthful: a first-delivery core
 * such as qoder has no agent-scoped runtime to probe or reset, which is exactly
 * what their documented {@code null} handling means.
 */
@Slf4j
@Component
public class AdkProviderRegistry {

    private final Map<String, AdkProvider> providers;
    /** The production core ids of the shared catalog; empty when no catalog is wired. */
    private final Set<String> catalogCoreIds;
    private final String defaultProvider;

    /**
     * Production wiring: the legacy provider beans plus the core catalog, which
     * admits the cores whose runs are owned by the coordinator (Task 18 cutover).
     */
    @Autowired
    public AdkProviderRegistry(List<AdkProvider> providerList, AdkSystemProperties systemProperties,
                               CoreCatalog coreCatalog) {
        this(providerList, systemProperties, coreCatalog == null ? Set.of() : coreCatalog.coreIds());
    }

    /** Explicit wiring seam without a core catalog: only provider beans are supported (legacy contexts). */
    public AdkProviderRegistry(List<AdkProvider> providerList, AdkSystemProperties systemProperties) {
        this(providerList, systemProperties, Set.of());
    }

    /** Test/override seam with an explicit catalog-core set. */
    public AdkProviderRegistry(List<AdkProvider> providerList, AdkSystemProperties systemProperties,
                               Set<String> catalogCoreIds) {
        // Use LinkedHashMap to preserve insertion order from the list
        this.providers = providerList.stream()
                .collect(Collectors.toUnmodifiableMap(
                        AdkProvider::providerId, Function.identity()));

        if (providers.isEmpty()) {
            throw new IllegalStateException(
                    "No ADK providers registered. Please add at least one AdkProvider bean.");
        }
        this.catalogCoreIds = Set.copyOf(catalogCoreIds);

        String configuredDefault = systemProperties != null
                ? systemProperties.getDefaultProvider() : null;

        if (configuredDefault == null || configuredDefault.isBlank()
                || (!providers.containsKey(configuredDefault)
                        && !catalogCoreIds.contains(configuredDefault))) {
            // Fail closed: never fall back to another provider. A wrong default
            // would route runs to a core the operator did not select.
            throw new IllegalStateException("Unsupported ADK provider: configured default '"
                    + configuredDefault + "' is not one of the registered providers "
                    + providers.keySet() + "; the supported cores are exactly the registered ones"
                    + " and there is no fallback");
        }
        this.defaultProvider = configuredDefault;

        log.info("ADK providers registered: {} (default: {}, catalog cores: {})",
                providers.keySet(), defaultProvider, catalogCoreIds);
    }

    /**
     * Resolve the provider for the given agent.
     *
     * @param agent the agent to execute
     * @return the resolved provider bean, or {@code null} when the selection is a
     *         catalog core: its runtime is run-owned and opened through the core
     *         launcher/coordinator, never through a legacy provider bean
     * @throws IllegalStateException if the selection is unknown or removed
     */
    @Nullable
    public AdkProvider resolve(Agent agent) {
        if (providers.isEmpty()) {
            throw new IllegalStateException("No ADK providers available to resolve");
        }
        String pid = agent != null ? agent.getAdkProvider() : null;
        if (pid == null || pid.isBlank()) {
            pid = defaultProvider;
        }

        AdkProvider provider = providers.get(pid);
        if (provider != null) {
            return provider;
        }
        if (catalogCoreIds.contains(pid)) {
            // A production catalog core: accepted (it is one of the supported
            // cores) and served by the run-owned core path. No provider bean is
            // demanded and none is substituted.
            log.debug("Core '{}' is a catalog core served by the run-owned core path;"
                    + " no legacy provider bean is involved", pid);
            return null;
        }
        throw new IllegalStateException("Unsupported ADK provider '" + pid + "' for agent "
                + (agent == null ? "null" : agent.getId()) + "; registered providers: "
                + providers.keySet() + " (there is no fallback)");
    }

    /** All registered provider IDs. */
    public List<String> getProviderIds() {
        return List.copyOf(providers.keySet());
    }

    /** The configured default core id (a provider bean or a catalog core). */
    public String defaultProviderId() {
        return defaultProvider;
    }

    /** Look up a provider by ID (primarily for health-check / management endpoints). */
    public AdkProvider getProvider(String providerId) {
        return providers.get(providerId);
    }

    /** Whether the id is a production catalog core (served by the run-owned core path). */
    public boolean isCatalogCore(String coreId) {
        return coreId != null && catalogCoreIds.contains(coreId);
    }

    /** Every core id this registry accepts: provider beans plus catalog cores, in order. */
    public Set<String> acceptedCoreIds() {
        Set<String> accepted = new LinkedHashSet<>(providers.keySet());
        accepted.addAll(catalogCoreIds);
        return Set.copyOf(accepted);
    }
}
