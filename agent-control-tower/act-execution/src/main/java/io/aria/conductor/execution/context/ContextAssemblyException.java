package io.aria.conductor.execution.context;

/**
 * A required contributor could not produce its section, so the envelope would
 * be incomplete; the run fails with an error naming the contributor (spec
 * §8.1, "A missing required section fails the run with a named error").
 */
public class ContextAssemblyException extends RuntimeException {

    private final String contributorId;

    public ContextAssemblyException(String contributorId, Throwable cause) {
        super("context contributor '" + contributorId + "' produced no required section"
                + (cause == null ? "" : ": " + cause.getMessage()), cause);
        this.contributorId = contributorId;
    }

    public String contributorId() {
        return contributorId;
    }
}
