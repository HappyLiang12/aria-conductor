package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.runtime.ExecutionMode;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The one selected backend per execution mode. Exactly one backend may be
 * registered for a mode: a second registration is a wiring error, and an
 * unregistered mode is refused explicitly instead of falling back to another
 * placement.
 */
public final class ExecutionBackendRegistry {

    private final Map<ExecutionMode, ExecutionBackend> backends;

    /**
     * @param selected the mode-specific backends; one per mode, copied
     *                 defensively so later mutations of the list are not observed
     */
    public ExecutionBackendRegistry(List<ExecutionBackend> selected) {
        Objects.requireNonNull(selected, "Selected backends are required");
        Map<ExecutionMode, ExecutionBackend> byMode = new EnumMap<>(ExecutionMode.class);
        for (ExecutionBackend backend : selected) {
            Objects.requireNonNull(backend, "A selected backend is required");
            Objects.requireNonNull(backend.mode(), "A selected backend must declare its mode");
            ExecutionBackend previous = byMode.put(backend.mode(), backend);
            if (previous != null) {
                throw new IllegalStateException("More than one execution backend is registered for mode "
                        + backend.mode() + ": " + previous.getClass().getSimpleName()
                        + " and " + backend.getClass().getSimpleName());
            }
        }
        if (byMode.isEmpty()) {
            throw new IllegalStateException("No execution backend is registered");
        }
        this.backends = Map.copyOf(byMode);
    }

    /** The one selected backend of the mode; never null. */
    public ExecutionBackend require(ExecutionMode mode) {
        Objects.requireNonNull(mode, "Execution mode is required");
        ExecutionBackend backend = backends.get(mode);
        if (backend == null) {
            throw new IllegalStateException("No execution backend is registered for mode " + mode
                    + "; registered modes: " + backends.keySet());
        }
        return backend;
    }

    /** The modes this registry can place a run in. */
    public Set<ExecutionMode> modes() {
        return backends.keySet();
    }

    /** The selected backends, in mode order. */
    public List<ExecutionBackend> all() {
        return List.copyOf(backends.values());
    }
}
