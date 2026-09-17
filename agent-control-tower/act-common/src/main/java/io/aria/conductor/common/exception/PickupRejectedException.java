package io.aria.conductor.common.exception;

import java.util.Map;

/**
 * A synchronous operator action that conflicts with the current state of a
 * kanban card or its linked run — "you asked for this move, and it cannot be
 * honoured". Carries a stable {@code code} and structured {@code details} so
 * every consumer (dashboard, MCP tool, Aria handler) can state the real reason.
 *
 * <p>Deliberately distinct from {@link IllegalStateException}: that one is the
 * generic governance conflict, this one is a pickup/transition rejection whose
 * reason the caller is expected to render.
 */
public class PickupRejectedException extends RuntimeException {

    private final String code;
    private final Map<String, Object> details;

    public PickupRejectedException(String code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
