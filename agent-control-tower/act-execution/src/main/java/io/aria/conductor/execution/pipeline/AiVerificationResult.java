package io.aria.conductor.execution.pipeline;

/**
 * Result of Stage 3 AI safety verification.
 *
 * <p>Outcomes:
 * <ul>
 *     <li>{@link VerificationOutcome#PASS} — AI judged the action safe (or AI was skipped).</li>
 *     <li>{@link VerificationOutcome#FAIL} — AI judged the action unsafe; pipeline must block.</li>
 *     <li>{@link VerificationOutcome#WARN} — AI flagged concerns but allows proceeding (logged).</li>
 * </ul>
 */
public record AiVerificationResult(
        VerificationOutcome outcome,
        String reasoning,
        double confidence
) {
    public enum VerificationOutcome { PASS, FAIL, WARN }

    public static AiVerificationResult pass(String reasoning) {
        return new AiVerificationResult(VerificationOutcome.PASS, reasoning, 1.0);
    }

    public static AiVerificationResult fail(String reasoning, double confidence) {
        return new AiVerificationResult(VerificationOutcome.FAIL, reasoning, confidence);
    }

    public static AiVerificationResult warn(String reasoning, double confidence) {
        return new AiVerificationResult(VerificationOutcome.WARN, reasoning, confidence);
    }

    public boolean isFail() {
        return outcome == VerificationOutcome.FAIL;
    }

    public boolean isPass() {
        return outcome == VerificationOutcome.PASS;
    }
}
