package io.aria.conductor.test;

/**
 * Result returned by {@link MockAdkRuntime#executeAction(Action)}.
 *
 * @param success whether the simulated action succeeded
 * @param output  serialized output payload (free-form per action)
 * @param error   error message if {@code success} is {@code false}
 */
public record ActionResult(boolean success, String output, String error) {

    public static ActionResult ok(String output) {
        return new ActionResult(true, output, null);
    }

    public static ActionResult failure(String error) {
        return new ActionResult(false, null, error);
    }
}
