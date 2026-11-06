package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.runtime.AgentExecutionSettings;
import io.aria.conductor.common.runtime.ExecutionMode;

import java.time.Instant;
import java.util.UUID;

/**
 * The frozen input of one execution attempt: everything a backend, adapter and
 * credential resolver need, captured before launch and never re-resolved from
 * the agent's mutable settings (spec 3.2).
 */
public record ExecutionSpec(UUID runId, UUID agentId,
        String coreId, ExecutionMode mode, AgentExecutionSettings settings,
        String credentialRef, String configurationRevision, Instant deadline) {
}
