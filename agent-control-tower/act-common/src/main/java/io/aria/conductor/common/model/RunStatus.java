package io.aria.conductor.common.model;

public enum RunStatus {
    PENDING, INITIALIZING, RUNNING, PAUSED,
    /** The turn ended with a clarification question; the run parks until the operator answers or finalizes. */
    WAITING_INPUT,
    COMPLETED, FAILED, CANCELLED,
    /** Task-level run aborted by the engine (timeout / budget / approval denial). */
    ABORTED
}
