package io.aria.conductor.execution.adk.opencode;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.aria.conductor.execution.adk.TaskExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WireMock-backed tests for {@link OpenCodeHttpClient} against the opencode
 * serve HTTP API shape (see <a href="https://opencode.ai/docs/server/">docs</a>):
 * {@code POST /session}, {@code POST /session/:id/message},
 * {@code POST /session/:id/abort}, {@code GET /global/health}.
 */
@WireMockTest
class OpenCodeHttpClientTest {

    private OpenCodeHttpClient client;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmRuntimeInfo) {
        client = new OpenCodeHttpClient(wmRuntimeInfo.getHttpBaseUrl(), Duration.ofSeconds(5));
    }

    @Test
    void createSession_returnsSessionId() {
        stubFor(post(urlEqualTo("/session"))
                .withRequestBody(matchingJsonPath("$.title", equalTo("run-42")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":"sess-abc","title":"run-42","parentID":null,"version":1}
                                """)));

        String sessionId = client.createSession("run-42");

        assertThat(sessionId).isEqualTo("sess-abc");
    }

    @Test
    void sendMessage_parsesTextPartsAndTokens() {
        stubFor(post(urlEqualTo("/session/sess-abc/message"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "info": {
                                    "id": "msg-9",
                                    "sessionID": "sess-abc",
                                    "role": "assistant",
                                    "tokens": { "input": 120, "output": 45, "reasoning": 10, "cache": 0 }
                                  },
                                  "parts": [
                                    { "id": "p1", "type": "reasoning", "text": "thinking..." },
                                    { "id": "p2", "type": "text", "text": "Hello from OpenCode" }
                                  ]
                                }
                                """)));

        OpenCodeHttpClient.MessageResponse resp = client.sendMessage("sess-abc", "system-rules", "do the task");

        assertThat(resp.messageId()).isEqualTo("msg-9");
        assertThat(resp.finalOutput()).isEqualTo("Hello from OpenCode");
        assertThat(resp.inputTokens()).isEqualTo(120);
        assertThat(resp.outputTokens()).isEqualTo(45);
    }

    @Test
    void sendMessage_concatenatesMultipleTextParts() {
        stubFor(post(urlEqualTo("/session/sess-1/message"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("""
                                {
                                  "info": { "id": "m1", "tokens": { "input": 5, "output": 5 } },
                                  "parts": [
                                    { "id": "a", "type": "text", "text": "line one" },
                                    { "id": "b", "type": "text", "text": "line two" }
                                  ]
                                }
                                """)));

        OpenCodeHttpClient.MessageResponse resp = client.sendMessage("sess-1", null, "hi");

        assertThat(resp.finalOutput()).isEqualTo("line one\nline two");
    }

    @Test
    void abortSession_returnsTrue() {
        stubFor(post(urlEqualTo("/session/sess-abc/abort"))
                .willReturn(aResponse().withStatus(200).withBody("true")));

        assertThat(client.abortSession("sess-abc")).isTrue();
    }

    @Test
    void isHealthy_returnsTrue_whenServerHealthy() {
        stubFor(get(urlEqualTo("/global/health"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"healthy\":true,\"version\":\"0.14.0\"}")));

        assertThat(client.isHealthy()).isTrue();
    }

    @Test
    void isHealthy_returnsFalse_whenServerUnhealthy() {
        stubFor(get(urlEqualTo("/global/health"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"healthy\":false,\"version\":\"0.14.0\"}")));

        assertThat(client.isHealthy()).isFalse();
    }

    @Test
    void isHealthy_returnsFalse_whenServerUnreachable() {
        // No stub registered → WireMock returns 404
        assertThat(client.isHealthy()).isFalse();
    }

    @Test
    void http500_mapsToTaskExecutionExceptionProviderError() {
        stubFor(post(urlEqualTo("/session"))
                .willReturn(aResponse().withStatus(500).withBody("internal error")));

        assertThatThrownBy(() -> client.createSession("run-x"))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                        .isEqualTo(TaskExecutionException.Cause.PROVIDER_ERROR))
                .hasMessageContaining("500");
    }

    @Test
    void messageHttp500_mapsToTaskExecutionExceptionProviderError() {
        stubFor(post(urlEqualTo("/session/sess-abc/message"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client.sendMessage("sess-abc", "sys", "task"))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                        .isEqualTo(TaskExecutionException.Cause.PROVIDER_ERROR));
    }

    @Test
    void abortHttp500_mapsToTaskExecutionExceptionProviderError() {
        stubFor(post(urlEqualTo("/session/sess-abc/abort"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client.abortSession("sess-abc"))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                        .isEqualTo(TaskExecutionException.Cause.PROVIDER_ERROR));
    }
}
