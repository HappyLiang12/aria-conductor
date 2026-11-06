package io.aria.conductor.execution.runtime;

import io.aria.conductor.execution.llm.LlmMessage;

import java.util.List;

/**
 * One prompt to a core session: the system material, the accepted conversation
 * history (defensively copied -- a late mutation of the caller's list cannot
 * change what was sent) and the current user prompt.
 */
public record CoreTask(String systemPrompt,
        List<LlmMessage> history,
        String userPrompt) {
    public CoreTask {
        history = List.copyOf(history);
    }
}
