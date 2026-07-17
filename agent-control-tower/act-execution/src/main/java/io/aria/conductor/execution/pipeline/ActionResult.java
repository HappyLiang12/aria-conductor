package io.aria.conductor.execution.pipeline;

/**
 * Result of executing a single action through the pipeline.
 */
public record ActionResult(
        Status status,
        String output,
        String error
) {
    public enum Status {
        SUCCESS, BLOCKED, DENIED, FAILED
    }

    public static ActionResult success() {
        return new ActionResult(Status.SUCCESS, "Action executed successfully", null);
    }

    public static ActionResult success(String output) {
        return new ActionResult(Status.SUCCESS, output, null);
    }

    public static ActionResult blocked(String reason) {
        return new ActionResult(Status.BLOCKED, null, reason);
    }

    public static ActionResult denied(String reason) {
        return new ActionResult(Status.DENIED, null, reason);
    }

    public static ActionResult failed(String error) {
        return new ActionResult(Status.FAILED, null, error);
    }
}
