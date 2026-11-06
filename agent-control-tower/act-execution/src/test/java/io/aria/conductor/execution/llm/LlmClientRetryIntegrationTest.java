package io.aria.conductor.execution.llm;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.aria.conductor.agent.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@WireMockTest(httpPort = 0)
@ExtendWith(MockitoExtension.class)
class LlmClientRetryIntegrationTest {

    @Mock private SystemConfigService configService;

    private LlmClientRetryDecorator decorator;
    private DefaultLlmClient rawClient;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmRuntimeInfo) {
        int port = wmRuntimeInfo.getHttpPort();
        when(configService.getInt("llm.retry.max.attempts", 3, 0, 10)).thenReturn(2);
        when(configService.getInt("llm.retry.backoff.base.ms", 2000, 500, 30000)).thenReturn(100);
        when(configService.getInt(eq("llm.request.timeout.seconds"), anyInt(), anyInt(), anyInt())).thenReturn(30);

        rawClient = createDefaultLlmClient("http://localhost:" + port);
        decorator = new LlmClientRetryDecorator(rawClient, configService);
    }

    @Test
    void shouldRetryOn504AndSucceed() {
        stubFor(post(urlEqualTo("/chat/completions"))
                .inScenario("retry")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(504).withHeader("Content-Type", "application/json"))
                .willSetStateTo("second"));

        stubFor(post(urlEqualTo("/chat/completions"))
                .inScenario("retry")
                .whenScenarioStateIs("second")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"choices\":[{\"message\":{\"content\":\"Hello!\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}")));

        LlmRequest request = LlmRequest.of("test", List.of(LlmMessage.user("hi")), 100);
        LlmResponse response = decorator.complete(request);

        assertThat(response.content()).isEqualTo("Hello!");
        verify(2, postRequestedFor(urlEqualTo("/chat/completions")));
    }

    @Test
    void shouldFailImmediatelyOn401() {
        stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(401).withHeader("Content-Type", "application/json").withBody("Unauthorized")));

        LlmRequest request = LlmRequest.of("test", List.of(LlmMessage.user("hi")), 100);

        assertThatThrownBy(() -> decorator.complete(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("401");

        verify(1, postRequestedFor(urlEqualTo("/chat/completions")));
    }

    private DefaultLlmClient createDefaultLlmClient(String baseUrl) {
        LlmProperties properties = new LlmProperties();
        properties.setBaseUrl(baseUrl);
        properties.setApiKey("test-key");
        properties.setModel("test-model");
        properties.setMaxTokens(100);

        io.aria.conductor.agent.repository.LlmProviderRepository providerRepo =
                org.mockito.Mockito.mock(io.aria.conductor.agent.repository.LlmProviderRepository.class);
        when(providerRepo.findByActiveTrue()).thenReturn(java.util.Optional.empty());

        return new DefaultLlmClient(properties, providerRepo, configService);
    }
}
