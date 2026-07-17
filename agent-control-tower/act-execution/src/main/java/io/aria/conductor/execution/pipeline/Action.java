package io.aria.conductor.execution.pipeline;

/**
 * Represents a single action/tool call to be executed within the agent loop.
 */
public record Action(
        String name,
        ActionType type,
        String arguments,
        String toolCallId
) {
    /**
     * Stable identifier for this action — falls back to the action name when
     * the LLM did not assign a tool call id.
     */
    public String id() {
        return toolCallId != null && !toolCallId.isBlank() ? toolCallId : name;
    }

    /**
     * Whether the action's effects can be inspected/recovered manually
     * via a pre-execution shadow copy. WRITE / EXECUTE / HIGH_RISK actions
     * are considered reversible (i.e. eligible for shadow capture);
     * pure READ actions are not.
     */
    public boolean isReversible() {
        return type == ActionType.WRITE
                || type == ActionType.EXECUTE
                || type == ActionType.HIGH_RISK;
    }

    /**
     * Pre-execution state snapshot for shadow copy capture.
     * For tool calls we use the raw arguments JSON as the best available
     * pre-state representation.
     */
    public String currentState() {
        return arguments != null ? arguments : "";
    }
}
