package io.aria.conductor.execution.approval;

/**
 * Typed rejection of an operator decision for an ACP permission ask: the request was well-formed
 * but cannot be applied to the ask as it stands. The {@link Code} travels to every operator
 * surface (HTTP 409 body, MCP tool error type) so a surface can tell a resendable race from an
 * ask shape that can never be approved.
 *
 * <p>Never carries ask payloads or secrets — the message states the ask id and the reason only.
 */
public class AcpDecisionRejectedException extends RuntimeException {

    /** Why the decision was rejected; one code per operator-visible outcome. */
    public enum Code {
        /** The ask's decision window was already over (or closed while the decision was in flight). */
        EXPIRED,
        /** The ask already carries a different terminal decision. */
        ALREADY_DECIDED,
        /** Approving this ask has no allow-once option to echo back. */
        UNSUPPORTED_OPTIONS,
        /** The approval is marked as an ACP ask but its companion record is missing. */
        INCONSISTENT_ASK,
        /** The ask's input was truncated by the bridge (R4): it can never be authorized, so an
         * approval is refused while deny and cancel stay available. */
        UNDECIDABLE_ASK,
        /** The ask's one-use write grant was already consumed: a retry must never authorize a
         * second execution of the same approved invocation (F2). */
        GRANT_ALREADY_CONSUMED
    }

    private final Code code;

    public AcpDecisionRejectedException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
