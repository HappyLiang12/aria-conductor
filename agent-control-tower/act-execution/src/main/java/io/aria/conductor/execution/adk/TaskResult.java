package io.aria.conductor.execution.adk;

import java.util.UUID;

/**
 * Result of a task-level execution performed by a task-capable {@link AdkProvider}.
 *
 * <p>Token counters are honest only together with {@link #usageReported}: when a provider
 * cannot measure usage, it must report {@code usageReported == false} (the int counters are
 * then a 0 placeholder that must never be presented as measured usage or folded into budget
 * accounting) instead of fabricating zeros.
 *
 * @param runId         identifier of the executed run
 * @param sessionId     provider-side session identifier (e.g. OpenCode session id)
 * @param finalOutput   aggregated final output produced by the agent
 * @param inputTokens   prompt tokens consumed by the run; 0 when {@code usageReported} is false
 * @param outputTokens  completion tokens produced by the run; 0 when {@code usageReported} is false
 * @param aborted       {@code true} if the run was aborted (timeout / budget / user cancel)
 * @param usageReported {@code true} only when the provider actually reported both counters as
 *                      measurements; {@code false} means no measurement exists for this run
 */
public record TaskResult(UUID runId, String sessionId, String finalOutput,
                         int inputTokens, int outputTokens, boolean aborted,
                         boolean usageReported) {
}
