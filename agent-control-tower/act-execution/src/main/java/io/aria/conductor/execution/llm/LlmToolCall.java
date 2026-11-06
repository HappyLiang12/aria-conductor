package io.aria.conductor.execution.llm;

public record LlmToolCall(
        String id,
        String name,
        String arguments
) {}
