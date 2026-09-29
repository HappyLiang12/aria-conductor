package io.aria.conductor.execution.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The production core adapters, keyed by core id (Task 18 cutover). Exactly one
 * adapter may be registered per core: a second registration is a wiring error,
 * and an unregistered core is refused explicitly instead of falling back to
 * another core.
 */
public final class CoreAdapters {

    private final Map<String, CoreAdapter> adapters;

    /**
     * @param registered the production adapters; copied defensively so later
     *                   mutations of the list are not observed
     */
    public CoreAdapters(List<CoreAdapter> registered) {
        Objects.requireNonNull(registered, "Registered adapters are required");
        Map<String, CoreAdapter> byCore = new LinkedHashMap<>();
        for (CoreAdapter adapter : registered) {
            Objects.requireNonNull(adapter, "A registered adapter is required");
            CoreAdapter previous = byCore.put(adapter.coreId(), adapter);
            if (previous != null) {
                throw new IllegalStateException("More than one adapter is registered for core "
                        + adapter.coreId());
            }
        }
        if (byCore.isEmpty()) {
            throw new IllegalStateException("No core adapter is registered");
        }
        this.adapters = Map.copyOf(byCore);
    }

    /** The one selected adapter of the core; never null. */
    public CoreAdapter require(String coreId) {
        Objects.requireNonNull(coreId, "Core id is required");
        CoreAdapter adapter = adapters.get(coreId);
        if (adapter == null) {
            throw new IllegalStateException("Unsupported agent core '" + coreId
                    + "'; registered cores: " + adapters.keySet());
        }
        return adapter;
    }

    /** Whether the core is a registered production core. */
    public boolean supports(String coreId) {
        return coreId != null && adapters.containsKey(coreId);
    }

    /** The registered production core ids. */
    public Set<String> coreIds() {
        return adapters.keySet();
    }
}
