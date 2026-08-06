package io.aria.conductor.execution.adk;

import java.util.UUID;

/**
 * Result of a task-level execution performed by a task-capable {@link AdkProvider}.
 *
 * @param runId        identifier of the executed run
 * @param sessionId    provider-side session identifier (e.g. OpenCode session id)
 * @param finalOutput  aggregated final output produced by the agent
 * @param inputTokens  prompt tokens consumed by the run
 * @param outputTokens completion tokens produced by the run
 * @param aborted      {@code true} if the run was aborted (timeout / budget / user cancel)
 */
public record TaskResult(UUID runId, String sessionId, String finalOutput,
                         int inputTokens, int outputTokens, boolean aborted) {
}
