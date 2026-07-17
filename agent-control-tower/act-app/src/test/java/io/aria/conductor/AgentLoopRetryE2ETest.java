package io.aria.conductor;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.service.SystemConfigService;
import io.aria.conductor.common.model.*;
import io.aria.conductor.execution.engine.AgentLoopEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:agent_loop_retry_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@ActiveProfiles("test")
@Disabled("AgentLoopEngine routes through LangChainAdkProvider → Python subprocess. WireMock can't intercept internal ADK port. Needs AdkProviderRegistry mock approach (see ActIntegrationTest.ariaChatResponds for pattern). Will fix in follow-up.")
class AgentLoopRetryE2ETest {

    private static WireMockServer wireMockServer;

    @Autowired private AgentLoopEngine engine;
    @Autowired private RunRepository runRepository;
    @Autowired private AgentRepository agentRepository;

    private UUID agentId;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(options().dynamicPort());
        wireMockServer.start();
        configureFor("localhost", wireMockServer.port());
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("llm.provider.base-url", () -> "http://localhost:" + wireMockServer.port());
        registry.add("llm.provider.api-key", () -> "test-key");
        registry.add("llm.provider.model", () -> "test-model");
    }

    @Autowired private SystemConfigService systemConfigService;

    @BeforeEach
    void setUp() {
        wireMockServer.resetAll();
        // ponytail: fast backoff so E2E doesn't timeout on default 2s→4s→8s
        systemConfigService.upsert("llm.retry.max.attempts", "1", "E2E test");
        systemConfigService.upsert("llm.retry.backoff.base.ms", "100", "E2E test");
        agentId = UUID.randomUUID();
        Agent agent = Agent.builder()
                .id(agentId)
                .name("test-agent")
                .agentType(AgentType.NATIVE)
                .role("You are a test assistant.")
                .provider("test-provider")
                .model("test-model")
                .healthStatus(HealthStatus.HEALTHY)
                .createdAt(Instant.now())
                .build();
        agentRepository.save(agent);
    }

    @Test
    void shouldRetryOn504AndCompleteSuccessfully() throws Exception {
        // WireMock: first call 504, second call 200
        stubFor(post(urlEqualTo("/chat/completions"))
                .inScenario("retry-test")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse()
                        .withStatus(504)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"timeout\"}"))
                .willSetStateTo("second-call"));

        stubFor(post(urlEqualTo("/chat/completions"))
                .inScenario("retry-test")
                .whenScenarioStateIs("second-call")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"choices\":[{\"message\":{\"content\":\"Hello!\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}")));

        // Create run using builder
        UUID runId = UUID.randomUUID();
        Run run = Run.builder()
                .id(runId)
                .agentId(agentId)
                .promptSeed("Say hello")
                .status(RunStatus.PENDING)
                .maxIterations(3)
                .createdAt(Instant.now())
                .build();
        runRepository.save(run);

        // Start run
        engine.startRun(runId);

        // Poll for completion
        Run result = null;
        for (int i = 0; i < 30; i++) {
            result = runRepository.findById(runId).orElse(null);
            if (result != null && result.getStatus() != RunStatus.RUNNING
                    && result.getStatus() != RunStatus.PENDING
                    && result.getStatus() != RunStatus.INITIALIZING) {
                break;
            }
            Thread.sleep(1000);
        }

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.getIterationCount()).isGreaterThan(0);

        // Verify WireMock received exactly 2 requests
        verify(2, postRequestedFor(urlEqualTo("/chat/completions")));
    }
}
