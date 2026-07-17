package io.aria.conductor.test;

import java.util.Map;

/**
 * Minimal action descriptor passed to {@link MockAdkRuntime#executeAction(Action)}.
 * <p>
 * Mirrors the ADK subprocess action contract loosely so tests can express
 * intent without depending on the production ADK transport types.
 *
 * @param name       action identifier (e.g. {@code "shell.run"})
 * @param parameters action input keyed by parameter name (may be empty)
 */
public record Action(String name, Map<String, Object> parameters) {

    public Action {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Action name must not be blank");
        }
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }

    public static Action of(String name) {
        return new Action(name, Map.of());
    }

    public static Action of(String name, Map<String, Object> parameters) {
        return new Action(name, parameters);
    }
}
