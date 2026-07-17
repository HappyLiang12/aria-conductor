package io.aria.conductor.execution.pipeline;

/**
 * Result of classifying an action — determines risk level and approval requirements.
 */
public record ActionClassification(
        String riskLevel,
        boolean requiresApproval,
        String category
) {
    public static ActionClassification lowRisk(String category) {
        return new ActionClassification("LOW", false, category);
    }

    public static ActionClassification mediumRisk(String category) {
        return new ActionClassification("MEDIUM", false, category);
    }

    public static ActionClassification highRisk(String category) {
        return new ActionClassification("HIGH", true, category);
    }
}
