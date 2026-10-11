package io.aria.conductor.agent.dto;

import io.aria.conductor.common.runtime.ExecutionMode;

/**
 * Result of {@code PUT /api/v1/agents/runtime}: the runtime pair the fleet was
 * converged to (the inventory-normalized pair, never a raw null) and how many
 * agents were updated. Retired agents are neither touched nor counted.
 */
public record ApplyRuntimeResult(String adkProvider, ExecutionMode executionMode, int updated) {
}
