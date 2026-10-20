package io.aria.conductor.common.exception;

/** A bad request that names the surface the operator must repair. */
public class ActionableIllegalArgumentException extends IllegalArgumentException implements ActionableFailure {

    private final String code;
    private final ErrorSurface surface;

    public ActionableIllegalArgumentException(String message, String code, ErrorSurface surface) {
        super(message);
        this.code = code;
        this.surface = surface;
    }

    public ActionableIllegalArgumentException(String message, String code, ErrorSurface surface,
                                              Throwable cause) {
        super(message, cause);
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
