package io.aria.conductor.execution.llm;

import java.util.List;
import java.util.Map;

public record LlmRequest(
        String model,
        List<LlmMessage> messages,
        int maxTokens,
        double temperature,
        List<Map<String, Object>> tools
) {
    public static LlmRequest of(String model, List<LlmMessage> messages, int maxTokens) {
        return new LlmRequest(model, messages, maxTokens, 0.7, null);
    }

    public static LlmRequest of(String model, List<LlmMessage> messages, int maxTokens, double temperature) {
        return new LlmRequest(model, messages, maxTokens, temperature, null);
    }

    public static LlmRequest withTools(String model, List<LlmMessage> messages, int maxTokens, List<Map<String, Object>> tools) {
        return new LlmRequest(model, messages, maxTokens, 0.7, tools);
    }

    public static LlmRequest withTools(String model, List<LlmMessage> messages, int maxTokens, double temperature, List<Map<String, Object>> tools) {
        return new LlmRequest(model, messages, maxTokens, temperature, tools);
    }

    public boolean hasTools() {
        return tools != null && !tools.isEmpty();
    }
}
