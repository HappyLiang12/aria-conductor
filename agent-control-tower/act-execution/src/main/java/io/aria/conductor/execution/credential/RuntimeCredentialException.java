package io.aria.conductor.execution.credential;

/**
 * Exception thrown when a runtime credential (a deployment-scoped provider token such as
 * the Qoder PAT) cannot be stored or read.
 *
 * <p>The {@link Cause} discriminates the failure category so callers can map it onto API
 * outcomes. Neither the message nor the wrapped cause ever carries credential material:
 * cipher failures are wrapped without echoing the input or the ciphertext.
 */
public class RuntimeCredentialException extends RuntimeException {

    /** Failure categories for runtime credential operations. */
    public enum Cause {
        /** No encryption key is configured; the store refuses the Base64 development fallback. */
        KEY_NOT_CONFIGURED,
        /** No credential row exists for the requested provider. */
        NOT_CONFIGURED,
        /** The stored ciphertext could not be decrypted. */
        CIPHER_FAILED
    }

    private final Cause cause;

    public RuntimeCredentialException(Cause cause, String message) {
        super(message);
        this.cause = cause;
    }

    public RuntimeCredentialException(Cause cause, String message, Throwable t) {
        super(message, t);
        this.cause = cause;
    }

    /** The failure category of this exception. */
    public Cause cause() {
        return cause;
    }
}
