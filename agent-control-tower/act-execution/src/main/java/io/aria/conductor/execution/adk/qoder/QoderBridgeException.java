package io.aria.conductor.execution.adk.qoder;

/**
 * Exception thrown when a call to the in-sandbox Qoder bridge (C0.2) fails.
 *
 * <p>The {@link Cause} discriminates the failure category so callers can map it onto run
 * outcomes: the transport cases ({@link Cause#TIMEOUT}, {@link Cause#UNREACHABLE},
 * {@link Cause#INTERRUPTED}, {@link Cause#PROVIDER_ERROR}) and the typed wire cases
 * (401/404/409/422, {@link Cause#REPLAY_GAP} in particular).
 *
 * <p>Messages never carry credential material: the bridge redacts its own error reasons
 * (bridge token plus every session MCP header value) before they leave the sandbox, and this
 * client only ever includes the server's status code, error code and redacted reason.
 */
public class QoderBridgeException extends RuntimeException {

    /** Failure categories for Qoder bridge calls. */
    public enum Cause {
        /** 401: the bridge rejected the bearer token (missing, blank or wrong). */
        UNAUTHORIZED,
        /** 404: unknown bridge session, permission request or route. */
        NOT_FOUND,
        /** 400: the bridge rejected the request as malformed. */
        INVALID_REQUEST,
        /** 409 with an unrecognized error code: an unresolved bridge conflict. */
        CONFLICT,
        /** 409 {@code REPLAY_GAP}: the requested replay window is gone; resuming from 0 is not safe. */
        REPLAY_GAP,
        /** 409 {@code ALREADY_RESOLVED}: the permission was already decided with the other value. */
        ALREADY_RESOLVED,
        /** 409 {@code SESSION_ENDED}: prompt on an ended session, or the CLI is gone. */
        SESSION_ENDED,
        /** 413: the request body exceeded the bridge's 1 MiB cap. */
        PAYLOAD_TOO_LARGE,
        /** 422 {@code UNSUPPORTED_OPTIONS}: approval cannot be expressed (no {@code allow_once} option). */
        UNSUPPORTED_OPTIONS,
        /** 5xx or any other non-2xx status: the bridge or the CLI failed. */
        PROVIDER_ERROR,
        /** The request exceeded its timeout. */
        TIMEOUT,
        /** The bridge could not be reached (connection refused/reset or another I/O failure). */
        UNREACHABLE,
        /** The calling thread was interrupted while waiting on the bridge. */
        INTERRUPTED
    }

    private final Cause cause;

    public QoderBridgeException(Cause cause, String message) {
        super(message);
        this.cause = cause;
    }

    public QoderBridgeException(Cause cause, String message, Throwable t) {
        super(message, t);
        this.cause = cause;
    }

    /** The failure category of this exception. */
    public Cause cause() {
        return cause;
    }
}
