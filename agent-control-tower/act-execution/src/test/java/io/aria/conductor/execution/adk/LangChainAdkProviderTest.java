package io.aria.conductor.execution.adk;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.aria.conductor.agent.repository.LlmProviderRepository;
import io.aria.conductor.execution.llm.LlmMessage;
import io.aria.conductor.execution.llm.LlmProperties;
import io.aria.conductor.execution.llm.LlmResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
// Dynamic port: a fixed 9300 collides with locally running ADK dev servers
// (uvicorn defaults to 9300) and breaks the suite with "Failed to bind".
@WireMockTest
class LangChainAdkProviderTest {

    @Mock AdkProcessReaper reaper;
    @Mock LlmProviderRepository providerRepository;

    LangChainAdkProperties properties;
    LlmProperties llmProperties;
    LangChainAdkProvider provider;
    int wmPort;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmRuntimeInfo) {
        wmPort = wmRuntimeInfo.getHttpPort();
        properties = new LangChainAdkProperties();
        properties.setHost("127.0.0.1");
        properties.setPortRangeStart(9300);
        properties.setPortRangeEnd(9301);
        properties.setMaxRestartBackoffMs(30_000L);
        properties.setLlmBaseUrl("https://api.deepseek.com/v1");
        properties.setLlmDefaultModel("deepseek-chat");
        llmProperties = new LlmProperties();
        llmProperties.setMaxTokens(1000);
        provider = new LangChainAdkProvider(properties, llmProperties, reaper, providerRepository);
    }

    @Test
    void call_forwardsIntermediateSseEventsToSink() {
        UUID agentId = UUID.randomUUID();
        provider.putInstanceForTest(agentId, new AdkInstance(agentId, wmPort, null, Instant.now(), Instant.now(), true, 0));

        stubFor(post(urlEqualTo("/run"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody("event: thinking\ndata: {\"content\":\"hmm let me see\"}\n\n"
                                + "event: tool_call\ndata: {\"name\":\"bash\",\"arguments\":\"{}\"}\n\n"
                                + "event: response\ndata: {\"content\":\"done\",\"finish_reason\":\"stop\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}\n")));

        List<AdkStreamEvent> seen = new ArrayList<>();
        provider.call(agentId, List.of(LlmMessage.user("test")), List.of(), seen::add);

        assertThat(seen).hasSize(2);
        assertThat(seen.get(0).kind()).isEqualTo("thinking");
        assertThat(seen.get(0).content()).isEqualTo("hmm let me see");
        assertThat(seen.get(1).kind()).isEqualTo("tool_call");
        assertThat(seen.get(1).toolName()).isEqualTo("bash");
    }

    @Test
    void providerId_returnsLangchain() {
        assertThat(provider.providerId()).isEqualTo("langchain");
    }

    @Test
    void call_makesHttpRequest_andParsesSseResponse() {
        UUID agentId = UUID.randomUUID();

        // Seed a healthy instance at the WireMock port so call() can proceed
        provider.putInstanceForTest(agentId, new AdkInstance(agentId, wmPort, null, Instant.now(), Instant.now(), true, 0));

        stubFor(post(urlEqualTo("/run"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody("event: response\ndata: {\"content\":\"Hello from ADK\",\"finish_reason\":\"stop\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}\n")));

        stubFor(get(urlEqualTo("/health"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        LlmResponse response = provider.call(agentId,
                List.of(LlmMessage.user("test")), List.of());

        assertThat(response).isNotNull();
        assertThat(response.content()).isEqualTo("Hello from ADK");
        assertThat(response.finishReason()).isEqualTo("stop");
        assertThat(response.inputTokens()).isEqualTo(10);
        assertThat(response.outputTokens()).isEqualTo(5);
    }

    @Test
    void call_throwsWhenHttp500() {
        UUID agentId = UUID.randomUUID();
        provider.putInstanceForTest(agentId, new AdkInstance(agentId, wmPort, null, Instant.now(), Instant.now(), true, 0));

        stubFor(get(urlEqualTo("/health")).willReturn(aResponse().withStatus(200)));
        stubFor(post(urlEqualTo("/run")).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> provider.call(agentId, List.of(LlmMessage.user("test")), List.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("/run returned status 500")
                // Review P2-2: the provider's own status exception must not be
                // re-wrapped by its own catch block.
                .hasNoCause();
    }

    /**
     * F6 regression: a provider credential error must surface as an actionable,
     * user-friendly message — not the raw OPENAI_API_KEY environment dump.
     */
    @Test
    void call_surfacesFriendlyMessage_whenAdkReportsMissingCredentials() {
        UUID agentId = UUID.randomUUID();
        provider.putInstanceForTest(agentId, new AdkInstance(agentId, wmPort, null, Instant.now(), Instant.now(), true, 0));

        stubFor(get(urlEqualTo("/health")).willReturn(aResponse().withStatus(200)));
        stubFor(post(urlEqualTo("/run"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody("event: error\ndata: {\"message\":\"Missing credentials. Please pass an api_key, "
                                + "workload_identity, admin_api_key, or set the OPENAI_API_KEY or OPENAI_ADMIN_KEY "
                                + "environment variable.\"}\n")));

        assertThatThrownBy(() -> provider.call(agentId, List.of(LlmMessage.user("test")), List.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("No LLM provider credentials")
                .message().doesNotContain("OPENAI_API_KEY");
    }

    @Test
    void call_throwsFast_whenSubprocessFailsToStart() {
        UUID agentId = UUID.randomUUID();
        // Force a subprocess startup failure: a nonexistent python binary makes ProcessBuilder.start()
        // throw IOException, so startNewInstance records an instance with process==null and healthy=false.
        // call() must then fail fast instead of polling a dead port for 60s.
        properties.setPythonPath("nonexistent-python-binary-for-test");
        provider.putInstanceForTest(agentId,
                new AdkInstance(agentId, wmPort, null, Instant.now(), Instant.now(), false, 0));

        assertThatThrownBy(() -> provider.call(agentId, List.of(LlmMessage.user("test")), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADK subprocess failed to start for agent");
    }

    @Test
    void isHealthy_returnsFalseForUnknownAgent() {
        assertThat(provider.isHealthy(UUID.randomUUID())).isFalse();
    }

    @Test
    void isHealthy_returnsTrue_whenWireMockHealthIsOk() {
        UUID agentId = UUID.randomUUID();
        stubFor(get(urlEqualTo("/health")).willReturn(aResponse().withStatus(200)));

        AdkInstance seed = new AdkInstance(agentId, wmPort, null, Instant.now(), Instant.now(), true, 0);
        provider.putInstanceForTest(agentId, seed);

        assertThat(provider.isHealthy(agentId)).isTrue();
    }

    @Test
    void healthCheck_marksUnhealthy_andIncrementsFailures() {
        UUID agentId = UUID.randomUUID();
        // No /health stub registered → WireMock returns 404 → UNHEALTHY.
        AdkInstance seed = new AdkInstance(agentId, wmPort, null, Instant.now(), Instant.now(), true, 0);
        provider.putInstanceForTest(agentId, seed);

        provider.healthCheck();

        AdkInstance after = provider.getInstanceForTest(agentId).orElseThrow();
        assertThat(after.healthy()).isFalse();
        assertThat(after.consecutiveFailures()).isEqualTo(1);
    }

    @Test
    void computeBackoff_followsExponentialSchedule_cappedAtMax() {
        assertThat(provider.computeBackoffMs(1)).isEqualTo(1_000L);
        assertThat(provider.computeBackoffMs(2)).isEqualTo(2_000L);
        assertThat(provider.computeBackoffMs(3)).isEqualTo(4_000L);
        assertThat(provider.computeBackoffMs(4)).isEqualTo(8_000L);
        assertThat(provider.computeBackoffMs(5)).isEqualTo(16_000L);
        assertThat(provider.computeBackoffMs(6)).isEqualTo(30_000L);
        assertThat(provider.computeBackoffMs(99)).isEqualTo(30_000L);
    }

    @Test
    void restartUnhealthy_skipsHealthyInstance() {
        UUID agentId = UUID.randomUUID();
        AdkInstance healthy = new AdkInstance(agentId, wmPort, null, Instant.now(), Instant.now(), true, 0);
        provider.putInstanceForTest(agentId, healthy);

        provider.restartUnhealthy();

        AdkInstance after = provider.getInstanceForTest(agentId).orElseThrow();
        assertThat(after).isSameAs(healthy);
    }

    @Test
    void restartUnhealthy_skipsWhenBelowFailureThreshold() {
        UUID agentId = UUID.randomUUID();
        AdkInstance two = new AdkInstance(agentId, wmPort, null, Instant.now(), Instant.now(), false, 2);
        provider.putInstanceForTest(agentId, two);

        provider.restartUnhealthy();

        AdkInstance after = provider.getInstanceForTest(agentId).orElseThrow();
        assertThat(after.consecutiveFailures()).isEqualTo(2);
    }

    @Test
    void restartUnhealthy_respectsBackoff_window() {
        UUID agentId = UUID.randomUUID();
        AdkInstance pending = new AdkInstance(
                agentId, wmPort, null, Instant.now(), Instant.now(), false, 5,
                1, Instant.now().plusSeconds(60));
        provider.putInstanceForTest(agentId, pending);

        provider.restartUnhealthy();

        AdkInstance after = provider.getInstanceForTest(agentId).orElseThrow();
        assertThat(after.restartAttempts()).isEqualTo(1);
    }

    @Test
    void allocatePort_returnsPortsWithinRange() {
        for (int i = 0; i < 200; i++) {
            int port = provider.allocatePortForTest();
            assertThat(port).isBetween(9300, 9399);
        }
    }

    @Test
    void allocatePort_wrapsAroundCorrectly() {
        properties.setPortRangeStart(9300);
        properties.setPortRangeEnd(9302);
        provider = new LangChainAdkProvider(properties, llmProperties, reaper, providerRepository);

        int p1 = provider.allocatePortForTest();
        int p2 = provider.allocatePortForTest();
        int p3 = provider.allocatePortForTest();

        assertThat(p1).isEqualTo(9300);
        assertThat(p2).isEqualTo(9301);
        assertThat(p3).isEqualTo(9300);
    }
}
