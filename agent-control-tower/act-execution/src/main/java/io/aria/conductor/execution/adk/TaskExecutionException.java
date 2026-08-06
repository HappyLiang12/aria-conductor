package io.aria.conductor.execution.adk;

/**
 * Exception thrown when a task-level execution performed by a task-capable
 * {@link AdkProvider} fails.
 *
 * <p>The {@link Cause} discriminates the failure category so callers can map
 * it onto run state transitions (e.g. FAILED vs ABORTED).
 */
public class TaskExecutionException extends RuntimeException {

    /** Failure categories for task-level execution. */
    public enum Cause {
        /** Sandbox creation / serve startup failed or never became ready. */
        SANDBOX_UNAVAILABLE,
        /** The task exceeded its time budget. */
        TIMEOUT,
        /** The task was aborted (user cancel / budget abort). */
        ABORTED,
        /** The provider reported an HTTP / protocol error. */
        PROVIDER_ERROR
    }

    private final Cause cause;

    public TaskExecutionException(Cause cause) {
        super("Task execution failed: " + cause);
        this.cause = cause;
    }

    public TaskExecutionException(Cause cause, String message) {
        super(message);
        this.cause = cause;
    }

    public TaskExecutionException(Cause cause, String message, Throwable t) {
        super(message, t);
        this.cause = cause;
    }

    /** The failure category of this exception. */
    public Cause cause() {
        return cause;
    }
}
