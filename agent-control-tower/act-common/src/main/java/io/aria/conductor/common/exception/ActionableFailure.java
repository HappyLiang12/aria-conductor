package io.aria.conductor.common.exception;

/**
 * A failure the operator can resolve from a known surface. The stable
 * {@code code} identifies the failure and {@link #surface()} names where the
 * fix lives; the message stays the operator-facing explanation.
 */
public interface ActionableFailure {

    String code();

    ErrorSurface surface();
}
