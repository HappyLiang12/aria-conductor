package io.aria.conductor.execution.git;

/**
 * Thrown when a GitHub REST API call for the SDD branch handoff fails.
 * Carries the HTTP status code (0 for transport-level failures) alongside
 * a human-readable message so callers can react loudly.
 */
public class GitBranchException extends RuntimeException {

    private final int status;

    public GitBranchException(int status, String message) {
        super(message);
        this.status = status;
    }

    /** HTTP status code of the failed call, or 0 for transport/parse failures. */
    public int getStatus() {
        return status;
    }
}
