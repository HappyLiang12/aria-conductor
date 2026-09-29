package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.runtime.ExecutionMode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable catalog of the production cores and the execution modes each core
 * supports (plan section 3.1).
 *
 * <p>This is a small value type, deliberately not a second provider registry:
 * the production descriptors stay owned by the existing provider inventory, and
 * the production call site (agent create/update wiring, Task 18) feeds the
 * registered core/mode pairs in here so admission can reject unsupported
 * selections without importing a registry into the policy.
 */
public final class CoreCatalog {

    private final Map<String, Set<ExecutionMode>> modesByCore;

    /**
     * @param modesByCore the registered core IDs with the modes each core
     *                    supports; copied defensively, so later mutations of the
     *                    argument are not observed
     */
    public CoreCatalog(Map<String, Set<ExecutionMode>> modesByCore) {
        Objects.requireNonNull(modesByCore, "Core modes map is required");
        Map<String, Set<ExecutionMode>> copy = new LinkedHashMap<>();
        modesByCore.forEach((coreId, modes) -> {
            Objects.requireNonNull(coreId, "Core id is required");
            if (coreId.isBlank())
                throw new IllegalArgumentException("Core id must not be blank");
            copy.put(coreId, Set.copyOf(Objects.requireNonNull(modes,
                    "Supported modes are required for core: " + coreId)));
        });
        this.modesByCore = Collections.unmodifiableMap(copy);
    }

    /** The registered production core IDs, in registration order. */
    public Set<String> coreIds() {
        return modesByCore.keySet();
    }

    /** The modes the given core supports; empty for an unregistered core. */
    public Set<ExecutionMode> modesFor(String coreId) {
        Set<ExecutionMode> modes = modesByCore.get(coreId);
        return modes == null ? Set.of() : modes;
    }

    /** Whether the given core is registered and supports the given mode. */
    public boolean supports(String coreId, ExecutionMode mode) {
        return coreId != null && mode != null && modesFor(coreId).contains(mode);
    }
}
