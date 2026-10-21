package io.aria.conductor.common.exception;

import java.util.Objects;

/** A conflict that names the surface the operator must repair. */
public class ActionableIllegalStateException extends IllegalStateException implements ActionableFailure {

    private final String code;
    private final ErrorSurface surface;

    public ActionableIllegalStateException(String message, String code, ErrorSurface surface) {
        super(message);
        // The identity is consumed as code()/surface() by the engine and the
        // error handler; a null half would fail far from its creation.
        this.code = Objects.requireNonNull(code, "code");
        this.surface = Objects.requireNonNull(surface, "surface");
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public ErrorSurface surface() {
        return surface;
    }
}
