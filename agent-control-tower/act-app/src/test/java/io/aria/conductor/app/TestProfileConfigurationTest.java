package io.aria.conductor.app;

import io.aria.conductor.execution.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

class TestProfileConfigurationTest extends BaseH2IntegrationTest {

    @Autowired
    private Environment environment;

    @Autowired
    private LlmClient llmClient;

    @Test
    void shouldUseDedicatedTestH2DatabaseWithFlywayEnabled() {
        assertThat(environment.getProperty("spring.datasource.url"))
                .contains("jdbc:h2:mem:act_test")
                .contains("MODE=MySQL");
        assertThat(environment.getProperty("spring.flyway.enabled")).isEqualTo("true");
    }

    @Test
    void shouldWireNoopLlmClientForSharedTestProfile() {
        assertThat(llmClient.getClass().getName()).contains("NoopLlmTestConfig");
    }
}
