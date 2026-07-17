package io.aria.conductor.execution.adk;

import java.util.Map;

/**
 * Action input payload sent to the ADK runtime via POST /run.
 *
 * @param agentId   target agent identifier
 * @param sessionId conversation session id (may be null for first call)
 * @param input     user/system input text
 * @param context   arbitrary context map (may be null)
 */
public record AdkRunRequest(
        String agentId,
        String sessionId,
        String input,
        Map<String, Object> context
) {
    public static AdkRunRequest of(String agentId, String input) {
        return new AdkRunRequest(agentId, null, input, null);
    }
}
