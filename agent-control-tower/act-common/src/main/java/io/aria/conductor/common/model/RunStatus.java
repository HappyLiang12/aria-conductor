package io.aria.conductor.common.model;

public enum RunStatus {
    PENDING, INITIALIZING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED,
    /** Task-level run aborted by the engine (timeout / budget / approval denial). */
    ABORTED
}
