package io.aria.conductor.execution.runtime;

/**
 * How pause/resume is actually achieved for a core/mode combination:
 * a verified native checkpoint, a verified backend suspend of the run-owned
 * process tree, or no verified strategy at all. {@code UNVERIFIED} must be
 * reported honestly rather than claiming a pause the core cannot guarantee.
 */
public enum ControlStrategy {
    NATIVE_CHECKPOINT,
    BACKEND_SUSPEND,
    UNVERIFIED
}
