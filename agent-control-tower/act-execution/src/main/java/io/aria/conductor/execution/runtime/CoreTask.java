package io.aria.conductor.execution.runtime;

import io.aria.conductor.execution.llm.LlmMessage;

import java.util.List;

/**
 * One prompt to a core session: the system material, the accepted conversation
 * history (defensively copied -- a late mutation of the caller's list cannot
 * change what was sent), the current user prompt, and any binary attachments
 * that travel with it for this turn only.
 */
public record CoreTask(String systemPrompt,
        List<LlmMessage> history,
        String userPrompt,
        List<CoreAttachment> attachments) {
    public CoreTask {
        history = List.copyOf(history);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public CoreTask(String systemPrompt, List<LlmMessage> history, String userPrompt) {
        this(systemPrompt, history, userPrompt, List.of());
    }
}
