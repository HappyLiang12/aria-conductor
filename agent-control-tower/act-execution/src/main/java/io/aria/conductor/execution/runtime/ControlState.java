package io.aria.conductor.execution.runtime;

/**
 * Truthful control state of a run-owned runtime. An unverified request never
 * reports a state the runtime did not actually reach.
 */
public enum ControlState {
    RUNNING,
    PAUSED,
    STOPPED
}
