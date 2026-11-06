package io.aria.conductor.mcp.tools;

import java.util.UUID;

/**
 * C4 ruling 8: the single typed runtime exception for every worker governance
 * denial. The stable {@link Code} lets callers, the C5 review surface and tests
 * assert the exact reason without parsing prose.
 *
 * <p>The message names the tool, the code and the worker's run id — never the
 * invocation arguments (approval payloads and digests stay out of transport
 * errors and logs).
 */
public class WorkerGovernanceDeniedException extends RuntimeException {

    /** Stable denial codes. */
    public enum Code {
        /** The tool is not callable by a worker at all (pinned or defaulted). */
        OPERATOR_ONLY,
        /** A worker write needs a one-use grant bound to this exact invocation; none matched. */
        GRANT_REQUIRED,
        /** The invocation names another run/agent than the worker's own scope. */
        SCOPE_MISMATCH,
        /** The tool has no reviewed policy — unknown tools are never auto-allowed. */
        UNKNOWN_TOOL,
        /** The request was classified as a worker but carries no usable scope. */
        INVALID_IDENTITY
    }

    private final Code code;
    private final String toolName;
    private final UUID runId;

    public WorkerGovernanceDeniedException(Code code, String toolName, UUID runId) {
        super("MCP worker call denied: tool='" + toolName + "' code=" + code
                + (runId == null ? "" : " runId=" + runId));
        this.code = code;
        this.toolName = toolName;
        this.runId = runId;
    }

    public Code getCode() {
        return code;
    }

    public String getToolName() {
        return toolName;
    }

    /** Run the denied caller acted as, or null when no scope existed. */
    public UUID getRunId() {
        return runId;
    }
}
