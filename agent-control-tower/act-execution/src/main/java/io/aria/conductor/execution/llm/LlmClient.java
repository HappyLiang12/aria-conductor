package io.aria.conductor.execution.llm;

import reactor.core.publisher.Flux;

public interface LlmClient {

    LlmResponse complete(LlmRequest request);

    Flux<String> stream(LlmRequest request);
}
