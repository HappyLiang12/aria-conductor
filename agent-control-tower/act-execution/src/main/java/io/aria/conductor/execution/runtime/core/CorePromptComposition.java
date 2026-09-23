package io.aria.conductor.execution.runtime.core;

import io.aria.conductor.execution.llm.LlmMessage;

import java.util.List;
import java.util.Objects;

/**
 * The shared prompt-translation rule of both native cores (task 11, spec 5.x):
 * a {@link io.aria.conductor.execution.runtime.CoreTask} carries system
 * material, the accepted conversation history and the current user request, and
 * each core must receive all three without re-issuing earlier work.
 *
 * <p>The history is rendered as one ordered transcript of {@code role: content}
 * lines ({@code user}, {@code assistant}, {@code tool} ... in the stored order);
 * a tool message keeps its correlation id so a transcript is never ambiguous.
 * The transcript is material the core reads as context — it is sent as one
 * prompt, never as a sequence of native prompts or messages, so an earlier tool
 * action can never be executed a second time.
 *
 * <p>Core adapters use the pieces their protocol offers: the Qoder bridge
 * accepts one prompt text and therefore receives the joined form, while the
 * OpenCode message envelope has a native {@code system} member plus text parts.
 */
final class CorePromptComposition {

    private CorePromptComposition() {
    }

    /** One ordered transcript of the history, one {@code role: content} line per message. */
    static String transcript(List<LlmMessage> history) {
        Objects.requireNonNull(history, "history");
        StringBuilder text = new StringBuilder();
        for (LlmMessage message : history) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(label(message));
        }
        return text.toString();
    }

    /**
     * The joined prompt text of one core task: system material, the history
     * transcript and the current user request, in that order, separated by a
     * blank line. A task with neither system material nor history is sent
     * verbatim, so a single-turn prompt reaches the core byte-identically.
     */
    static String joined(String systemPrompt, List<LlmMessage> history, String userPrompt) {
        Objects.requireNonNull(history, "history");
        StringBuilder text = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            text.append(systemPrompt);
        }
        if (!history.isEmpty()) {
            if (text.length() > 0) {
                text.append("\n\n");
            }
            text.append(transcript(history));
        }
        if (text.length() > 0) {
            text.append("\n\n");
        }
        text.append(userPrompt == null ? "" : userPrompt);
        return text.toString();
    }

    private static String label(LlmMessage message) {
        String role = message.role() == null || message.role().isBlank() ? "message" : message.role();
        String correlation = message.toolCallId() == null || message.toolCallId().isBlank()
                ? ""
                : "[" + message.toolCallId() + "]";
        return role + correlation + ": " + (message.content() == null ? "" : message.content());
    }
}
