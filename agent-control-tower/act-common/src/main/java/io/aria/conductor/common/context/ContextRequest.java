package io.aria.conductor.common.context;

import io.aria.conductor.common.model.Sensitivity;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What a context contribution is assembled for (spec §8.1). Every optional
 * input defaults fail-closed: no ceiling means {@link Sensitivity#INTERNAL},
 * no budget means the global 24,000-character cap, and absent history or
 * client policy are empty rather than null.
 */
public record ContextRequest(
        UUID runId,
        String conversationId,
        UUID agentId,
        ContextAdapter adapter,
        int turnNumber,
        String goal,
        UUID providerId,
        Sensitivity maxSensitivity,
        int maxChars,
        String workspaceRef,
        String clientPolicy,
        List<ContextMessage> priorHistory) {

    public ContextRequest {
        Objects.requireNonNull(adapter, "adapter");
        goal = goal == null ? "" : goal;
        maxSensitivity = maxSensitivity == null ? Sensitivity.INTERNAL : maxSensitivity;
        maxChars = maxChars <= 0 ? 24_000 : maxChars;
        clientPolicy = clientPolicy == null ? "" : clientPolicy;
        priorHistory = priorHistory == null ? List.of() : List.copyOf(priorHistory);
    }

    public ContextRequest withMaxSensitivity(Sensitivity ceiling) {
        return new ContextRequest(runId, conversationId, agentId, adapter, turnNumber,
                goal, providerId, ceiling, maxChars, workspaceRef, clientPolicy, priorHistory);
    }
}
