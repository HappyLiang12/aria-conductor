package io.aria.conductor.agent.dto;

import io.aria.conductor.common.runtime.ExecutionMode;

/**
 * Body of {@code PUT /api/v1/agents/runtime}: the ONE (core, mode) pair the whole
 * fleet converges to. Both fields are applied explicitly to every non-retired
 * agent, so a batch apply never stores a null selection the uniformity guard
 * would have to normalize (guard consistency, 2026-10-11).
 */
public record ApplyRuntimeRequest(String adkProvider, ExecutionMode executionMode) {
}
