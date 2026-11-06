package io.aria.conductor.execution.runtime;

/**
 * Acknowledgment of a control request. {@code verified} distinguishes an
 * actually confirmed runtime state from an unverified request, so callers never
 * persist a control state the runtime did not reach.
 */
public record ControlAck(ControlState state, boolean verified) {
}
