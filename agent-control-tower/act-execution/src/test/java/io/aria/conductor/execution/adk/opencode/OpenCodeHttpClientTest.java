package io.aria.conductor.execution.adk.opencode;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.aria.conductor.execution.adk.TaskExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
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

    @AfterEach
    void tearDown() {
        // Each test creates its own client with a fresh executor — release it so
        // the test JVM does not accumulate threads (see #15).
        client.close();
    }

    @Test
    void openSession_returnsSessionId() {
        stubFor(post(urlEqualTo("/session"))
                .withRequestBody(matchingJsonPath("$.title", equalTo("run-42")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":"sess-abc","title":"run-42","parentID":null,"version":1}
                                """)));

        String sessionId = client.openSession("run-42");

        assertThat(sessionId).isEqualTo("sess-abc");
    }

    @Test
    void abortSession_returnsTrue() {
        stubFor(post(urlEqualTo("/session/sess-abc/abort"))
                .willReturn(aResponse().withStatus(200).withBody("true")));

        assertThat(client.abortSession("sess-abc")).isTrue();
    }

    @Test
    void sendPrompt_omitsTheModelMemberForThePinnedCorePayload() {
        // The pinned opencode (>= 1.18) rejects a string model member on
        // /session/:id/message ("Expected object | null, got \"...\" at [\"model\"]"),
        // so the governed prompt sends system + parts only; the server then uses
        // its configured/default model.
        stubFor(post(urlEqualTo("/session/sess-abc/message"))
                .withRequestBody(notMatching(".*model.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "info": { "id": "m9", "tokens": { "input": 10, "output": 20 } },
                                  "parts": [ { "id": "p1", "type": "text", "text": "ok" } ]
                                }
                                """)));

        OpenCodeHttpClient.MessageResult result =
                client.sendPrompt("sess-abc", "sys", List.of("do the task"), Duration.ofSeconds(5));

        assertThat(result.finalOutput()).isEqualTo("ok");
        assertThat(result.messageId()).isEqualTo("m9");
        assertThat(result.inputTokens()).isEqualTo(10);
    }

    @Test
    void sendPrompt_concatenatesMultipleTextParts() {
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

        OpenCodeHttpClient.MessageResult result =
                client.sendPrompt("sess-1", null, List.of("hi"), Duration.ofSeconds(5));

        assertThat(result.finalOutput()).isEqualTo("line one\nline two");
    }

    @Test
    void sendPrompt_surfaces400ModelPayloadRefusalWithDetail() {
        // A model-payload refusal (the shape opencode served for the pre-fix
        // payload) carries a native BadRequest envelope; the client folds the
        // detail into the refusal instead of reporting the bare status.
        stubFor(post(urlEqualTo("/session/sess-abc/message"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"name":"BadRequest","data":{"message":"Expected object | null, got \\"gpt-4o\\" at [\\"model\\"]","kind":"Payload"}}
                                """)));

        assertThatThrownBy(() -> client.sendPrompt("sess-abc", "sys",
                List.of("do the task"), Duration.ofSeconds(5)))
                .isInstanceOf(TaskExecutionException.class)
                .hasMessageContaining("Expected object | null");
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
    void sessionHttp500_mapsToTaskExecutionExceptionProviderError() {
        stubFor(post(urlEqualTo("/session"))
                .willReturn(aResponse().withStatus(500).withBody("internal error")));

        assertThatThrownBy(() -> client.openSession("run-x"))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                        .isEqualTo(TaskExecutionException.Cause.PROVIDER_ERROR))
                .hasMessageContaining("500");
    }

    @Test
    void messageHttp500_mapsToTaskExecutionExceptionProviderError() {
        stubFor(post(urlEqualTo("/session/sess-abc/message"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client.sendPrompt("sess-abc", "sys",
                List.of("task"), Duration.ofSeconds(5)))
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

    // ---- listMessages (progress pump data source) ----

    @Test
    void listMessages_parsesMessageAndPartSnapshots() {
        stubFor(get(urlEqualTo("/session/sess-abc/message"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [
                                  {
                                    "info": { "id": "m1", "role": "assistant" },
                                    "parts": [
                                      { "id": "p1", "type": "reasoning", "text": "locating scheduler" },
                                      { "id": "p2", "type": "tool", "tool": "bash", "state": { "status": "completed", "output": "ok" } }
                                    ]
                                  },
                                  {
                                    "info": { "id": "m2", "role": "assistant" },
                                    "parts": [ { "id": "p3", "type": "text", "text": "done" } ]
                                  }
                                ]
                                """)));

        OpenCodeHttpClient.MessageListResult result = client.listMessages("sess-abc");

        assertThat(result.degraded()).isFalse();
        List<OpenCodeHttpClient.MessageSnapshot> snaps = result.messages();
        assertThat(snaps).hasSize(2);
        assertThat(snaps.get(0).id()).isEqualTo("m1");
        assertThat(snaps.get(0).parts()).hasSize(2);
        assertThat(snaps.get(0).parts().get(0).id()).isEqualTo("p1");
        assertThat(snaps.get(0).parts().get(0).type()).isEqualTo("reasoning");
        assertThat(snaps.get(0).parts().get(0).text()).isEqualTo("locating scheduler");
        assertThat(snaps.get(0).parts().get(1).toolName()).isEqualTo("bash");
        assertThat(snaps.get(0).parts().get(1).state()).isEqualTo("completed");
        assertThat(snaps.get(0).parts().get(1).output()).isEqualTo("ok");
        assertThat(snaps.get(1).id()).isEqualTo("m2");
        assertThat(snaps.get(1).parts().get(0).text()).isEqualTo("done");
    }

    @Test
    void listMessages_requestsTheExactSessionMessagesPath() {
        stubFor(get(urlEqualTo("/session/sess-abc/message"))
                .willReturn(aResponse().withStatus(200).withBody("[]")));

        OpenCodeHttpClient.MessageListResult result = client.listMessages("sess-abc");
        assertThat(result.messages()).isEmpty();
        assertThat(result.degraded())
                .as("a readable empty array is 'no data yet', not a degraded poll")
                .isFalse();

        verify(getRequestedFor(urlEqualTo("/session/sess-abc/message")));
    }

    @Test
    void listMessages_non2xxReturnsDegradedEmptyResultWithoutThrowing() {
        // No stub for the 404 case → WireMock answers 404; the pump must degrade,
        // never blow up the task.
        OpenCodeHttpClient.MessageListResult missing = client.listMessages("sess-missing");
        assertThat(missing.messages()).isEmpty();
        assertThat(missing.degraded()).isTrue();

        stubFor(get(urlEqualTo("/session/sess-abc/message"))
                .willReturn(aResponse().withStatus(500)));
        OpenCodeHttpClient.MessageListResult serverError = client.listMessages("sess-abc");
        assertThat(serverError.messages()).isEmpty();
        assertThat(serverError.degraded()).isTrue();
    }

    @Test
    void listMessages_malformedBodyReturnsDegradedEmptyResultWithoutThrowing() {
        stubFor(get(urlEqualTo("/session/sess-abc/message"))
                .willReturn(aResponse().withStatus(200).withBody("{not json")));
        OpenCodeHttpClient.MessageListResult malformed = client.listMessages("sess-abc");
        assertThat(malformed.messages()).isEmpty();
        assertThat(malformed.degraded()).isTrue();

        stubFor(get(urlEqualTo("/session/sess-abc/message"))
                .willReturn(aResponse().withStatus(200).withBody("{\"not\":\"an array\"}")));
        OpenCodeHttpClient.MessageListResult nonArray = client.listMessages("sess-abc");
        assertThat(nonArray.messages()).isEmpty();
        assertThat(nonArray.degraded()).isTrue();
    }

    @Test
    void listMessages_connectionFailureReturnsDegradedEmptyResultWithoutThrowing() {
        stubFor(get(urlEqualTo("/session/sess-io/message"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        OpenCodeHttpClient.MessageListResult result = client.listMessages("sess-io");

        assertThat(result.messages()).isEmpty();
        assertThat(result.degraded()).isTrue();
    }

    @Test
    void listMessages_toleratesMissingPartFields() {
        stubFor(get(urlEqualTo("/session/sess-abc/message"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("""
                                [ { "info": { "id": "m1" }, "parts": [ { "type": "text" }, { } ] } ]
                                """)));

        OpenCodeHttpClient.MessageListResult result = client.listMessages("sess-abc");

        assertThat(result.degraded()).isFalse();
        List<OpenCodeHttpClient.MessageSnapshot> snaps = result.messages();
        assertThat(snaps).hasSize(1);
        assertThat(snaps.get(0).id()).isEqualTo("m1");
        assertThat(snaps.get(0).parts()).hasSize(2);
        assertThat(snaps.get(0).parts().get(0).id()).isNull();
        assertThat(snaps.get(0).parts().get(0).text()).isNull();
        assertThat(snaps.get(0).parts().get(0).toolName()).isNull();
        assertThat(snaps.get(0).parts().get(0).state()).isNull();
        assertThat(snaps.get(0).parts().get(0).output()).isNull();
        assertThat(snaps.get(0).parts().get(1).type()).isNull();
    }
}
