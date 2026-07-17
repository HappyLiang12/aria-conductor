package io.aria.conductor.execution.adk;

import java.util.List;

/**
 * SSE-parsed result from POST /run on the ADK runtime.
 *
 * @param sessionId  session id echoed back from the runtime (may be null)
 * @param output     final aggregated text from the SSE stream
 * @param events     all individual SSE event payloads (raw data: lines, in order)
 * @param success    whether the run completed without an error event
 * @param errorMessage error text (null if success)
 */
public record AdkRunResponse(
        String sessionId,
        String output,
        List<String> events,
        boolean success,
        String errorMessage
) {
    public static AdkRunResponse success(String sessionId, String output, List<String> events) {
        return new AdkRunResponse(sessionId, output, events, true, null);
    }

    public static AdkRunResponse failure(String message, List<String> events) {
        return new AdkRunResponse(null, null, events == null ? List.of() : events, false, message);
    }
}
