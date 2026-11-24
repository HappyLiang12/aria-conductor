package io.aria.conductor.execution.llm;

public interface LlmClient {

    LlmResponse complete(LlmRequest request);
}
