package io.aria.conductor.common.exception;

/** A conflict that names the surface the operator must repair. */
public class ActionableIllegalStateException extends IllegalStateException implements ActionableFailure {

    private final String code;
    private final ErrorSurface surface;

    public ActionableIllegalStateException(String message, String code, ErrorSurface surface) {
        super(message);
        this.code = code;
        this.surface = surface;
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
