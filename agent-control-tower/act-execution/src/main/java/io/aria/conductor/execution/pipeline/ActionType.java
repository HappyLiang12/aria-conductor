package io.aria.conductor.execution.pipeline;

/**
 * Classification of action types based on risk level.
 */
public enum ActionType {
    READ,
    WRITE,
    EXECUTE,
    HIGH_RISK
}
