package io.aria.conductor.execution.llm;

import java.util.List;

public record LlmResponse(
        String content,
        int inputTokens,
        int outputTokens,
        String finishReason,
        List<LlmToolCall> toolCalls
) {
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public int totalTokens() {
        return inputTokens + outputTokens;
    }
}
