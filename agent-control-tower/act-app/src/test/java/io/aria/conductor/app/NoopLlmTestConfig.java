package io.aria.conductor.app;

import io.aria.conductor.execution.llm.LlmClient;
import io.aria.conductor.execution.llm.LlmRequest;
import io.aria.conductor.execution.llm.LlmResponse;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Test configuration for the noop-llm profile that provides a noop LLM client
 * to avoid external HTTP calls in CI/local test environments.
 *
 * Overrides the resilientLlmClient bean by name to intercept all LLM calls.
 */
@TestConfiguration
@Profile("noop-llm")
public class NoopLlmTestConfig {

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
