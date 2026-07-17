package io.aria.conductor.execution.pipeline;

/**
 * Result of rule verification — determines if the action is allowed.
 */
public record RuleVerificationResult(
        boolean allowed,
        String reason
) {
    public boolean isAllowed() {
        return allowed;
    }

    public static RuleVerificationResult allow() {
        return new RuleVerificationResult(true, null);
    }

    public static RuleVerificationResult deny(String reason) {
        return new RuleVerificationResult(false, reason);
    }
}
