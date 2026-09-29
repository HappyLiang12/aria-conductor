package io.aria.conductor.execution.runtime;

import java.math.BigDecimal;

/**
 * Observed usage of one execution. Every component is nullable: an unobserved
 * counter stays unknown and must never be reported as zero.
 */
public record UsageSnapshot(Long inputTokens, Long outputTokens,
        BigDecimal credits, String observedModel) {
}
