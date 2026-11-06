package io.aria.conductor.execution.llm;

import java.util.List;

public record LlmMessage(
        String role,
        String content,
        String toolCallId,
        List<LlmToolCall> toolCalls
) {
    public static LlmMessage system(String content) {
        return new LlmMessage("system", content, null, null);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage("user", content, null, null);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage("assistant", content, null, null);
    }

    public static LlmMessage assistant(String content, List<LlmToolCall> toolCalls) {
        return new LlmMessage("assistant", content, null, toolCalls);
    }

    public static LlmMessage tool(String content, String toolCallId) {
        return new LlmMessage("tool", content, toolCallId, null);
    }
}
