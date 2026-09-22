package io.aria.conductor.execution.runtime;

/**
 * Terminal outcome of one core session: the core session identity, the final
 * output, observed usage and whether cancellation (rather than completion)
 * produced the outcome.
 */
public record CoreResult(String sessionId, String finalOutput,
        UsageSnapshot usage, boolean cancelled) {
}
