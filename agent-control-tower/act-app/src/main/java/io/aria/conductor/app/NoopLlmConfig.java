package io.aria.conductor.app;

import io.aria.conductor.execution.llm.LlmClient;
import io.aria.conductor.execution.llm.LlmRequest;
import io.aria.conductor.execution.llm.LlmResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * No-op LLM client for the {@code noop-llm} profile (local E2E / demo stacks without
 * a real LLM provider). Overrides the {@code resilientLlmClient} bean so every agent
 * run completes immediately with a canned response - deterministic, no external calls.
 */
@Configuration
@Profile("noop-llm")
public class NoopLlmConfig {

    @Bean
    @Primary
    public LlmClient resilientLlmClient() {
        return new LlmClient() {
            @Override
            public LlmResponse complete(LlmRequest request) {
                return new LlmResponse("Mock response for testing", 0, 0, "stop", List.of());
            }

            @Override
            public Flux<String> stream(LlmRequest request) {
                return Flux.just("Mock stream response for testing");
            }
        };
    }
}
