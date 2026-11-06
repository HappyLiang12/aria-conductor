package io.aria.conductor.execution.llm;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.aria.conductor.agent.repository.LlmProviderRepository;
import io.aria.conductor.agent.service.SystemConfigService;
import io.aria.conductor.common.model.LlmProviderType;
import io.aria.conductor.test.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Behaviour tests for {@link DefaultLlmClient} against a WireMock server bound to a dynamic
 * port (0 — never a fixed port). Covers request construction (Bearer vs Azure api-key auth,
 * model/max_tokens/messages body), response parsing (content, usage, tool calls), the
 * empty-choices sentinel, HTTP error → {@link LlmHttpException} mapping with status+body, the
 * active-provider precedence over YAML config, and the unsupported streaming path.
 */
@WireMockTest(httpPort = 0)
@ExtendWith(MockitoExtension.class)
class DefaultLlmClientWireMockTest {

    @Mock private SystemConfigService systemConfigService;
    @Mock private LlmProviderRepository providerRepository;

    private int port;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wm) {
        port = wm.getHttpPort();
        lenient().when(systemConfigService.getInt(eq("llm.request.timeout.seconds"), anyInt(), anyInt(), anyInt()))
                .thenReturn(30);
    }

    private DefaultLlmClient yamlClient() {
        LlmProperties props = new LlmProperties();
        props.setBaseUrl("http://localhost:" + port);
        props.setApiKey("sk-yaml-key");
        props.setModel("yaml-model");
        props.setMaxTokens(1234);
        lenient().when(providerRepository.findByActiveTrue()).thenReturn(Optional.empty());
        return new DefaultLlmClient(props, providerRepository, systemConfigService);
    }

    private LlmRequest request() {
        return LlmRequest.of("req-model", List.of(LlmMessage.user("hello there")), 77);
    }

    @Test
    void complete_sendsBearerAuth_andBuildsRequestBody() {
        stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"choices\":[{\"message\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}]," +
                        "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}")));

        LlmResponse response = yamlClient().complete(request());

        assertThat(response.content()).isEqualTo("hi");
        verify(postRequestedFor(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer sk-yaml-key"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("req-model")))
                .withRequestBody(matchingJsonPath("$.max_tokens", equalTo("77")))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("user")))
                .withRequestBody(matchingJsonPath("$.messages[0].content", equalTo("hello there"))));
    }

    @Test
    void complete_parsesContentUsageAndToolCalls() {
        stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"choices\":[{\"message\":{\"content\":\"calling\",\"tool_calls\":[" +
                        "{\"id\":\"call_1\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"p\\\":1}\"}}]}," +
                        "\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":22}}")));

        LlmResponse response = yamlClient().complete(request());

        assertThat(response.content()).isEqualTo("calling");
        assertThat(response.finishReason()).isEqualTo("tool_calls");
        assertThat(response.inputTokens()).isEqualTo(11);
        assertThat(response.outputTokens()).isEqualTo(22);
        assertThat(response.hasToolCalls()).isTrue();
        assertThat(response.toolCalls()).singleElement().satisfies(tc -> {
            assertThat(tc.id()).isEqualTo("call_1");
            assertThat(tc.name()).isEqualTo("read_file");
            assertThat(tc.arguments()).isEqualTo("{\"p\":1}");
        });
    }

    @Test
    void complete_emptyChoices_returnsErrorSentinel() {
        stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"choices\":[],\"usage\":{}}")));

        LlmResponse response = yamlClient().complete(request());

        assertThat(response.content()).isEmpty();
        assertThat(response.finishReason()).isEqualTo("error");
        assertThat(response.toolCalls()).isEmpty();
    }

    @Test
    void complete_httpError_throwsLlmHttpExceptionWithStatusAndBody() {
        stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse()
                .withStatus(429).withBody("rate limited")));

        assertThatThrownBy(() -> yamlClient().complete(request()))
                .isInstanceOf(LlmHttpException.class)
                .satisfies(e -> {
                    LlmHttpException ex = (LlmHttpException) e;
                    assertThat(ex.getStatusCode()).isEqualTo(429);
                    assertThat(ex.getResponseBody()).isEqualTo("rate limited");
                });
    }

    @Test
    void complete_prefersActiveProvider_andUsesAzureApiKeyHeader() {
        // An active AZURE provider takes precedence over YAML config and authenticates via api-key.
        when(providerRepository.findByActiveTrue()).thenReturn(Optional.of(
                TestDataBuilder.anLlmProvider()
                        .withType(LlmProviderType.AZURE)
                        .withBaseUrl("http://localhost:" + port)
                        .withApiKey("azure-secret")
                        .withActive(true)
                        .build()));
        stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{}}")));

        LlmProperties props = new LlmProperties();
        props.setBaseUrl("http://unused.example");
        props.setApiKey("sk-yaml");
        DefaultLlmClient client = new DefaultLlmClient(props, providerRepository, systemConfigService);

        LlmResponse response = client.complete(request());

        assertThat(response.content()).isEqualTo("ok");
        verify(postRequestedFor(urlEqualTo("/chat/completions"))
                .withHeader("api-key", equalTo("azure-secret"))
                .withoutHeader("Authorization"));
    }

    @Test
    void stream_isUnsupported_andReturnsErrorFlux() {
        DefaultLlmClient client = yamlClient();

        assertThatThrownBy(() -> client.stream(request()).blockFirst())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
