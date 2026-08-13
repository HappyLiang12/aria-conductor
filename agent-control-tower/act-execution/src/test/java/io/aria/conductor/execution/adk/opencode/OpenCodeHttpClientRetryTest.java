package io.aria.conductor.execution.adk.opencode;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.aria.conductor.execution.adk.TaskExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Retry semantics for {@link OpenCodeHttpClient}:
 * <ul>
 *   <li>transient I/O failures (connection reset) are retried with backoff;</li>
 *   <li>timeouts and non-2xx responses are NOT retried;</li>
 *   <li>abort / health probes bypass retry entirely (a dead sandbox must fail fast).</li>
 * </ul>
 */
@WireMockTest
class OpenCodeHttpClientRetryTest {

    private OpenCodeHttpClient client;
    private String baseUrl;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmRuntimeInfo) {
        baseUrl = wmRuntimeInfo.getHttpBaseUrl();
        client = new OpenCodeHttpClient(baseUrl, Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    @Test
    void retriesConnectionReset_thenSucceeds() {
        stubFor(post(urlEqualTo("/session"))
                .inScenario("retry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
                .willSetStateTo("failed-once"));
        stubFor(post(urlEqualTo("/session"))
                .inScenario("retry")
                .whenScenarioStateIs("failed-once")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"sess-1\"}")));

        String sessionId = client.createSession("run-1");

        assertThat(sessionId).isEqualTo("sess-1");
        verify(2, postRequestedFor(urlEqualTo("/session")));
    }

    @Test
    void timeout_isNotRetried() {
        stubFor(post(urlEqualTo("/session"))
                .willReturn(aResponse().withFixedDelay(1500).withStatus(200).withBody("{\"id\":\"sess-1\"}")));

        OpenCodeHttpClient shortClient = new OpenCodeHttpClient(baseUrl, Duration.ofMillis(300));
        try {
            assertThatThrownBy(() -> shortClient.createSession("run-x"))
                    .isInstanceOf(TaskExecutionException.class)
                    .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                            .isEqualTo(TaskExecutionException.Cause.TIMEOUT));
            verify(1, postRequestedFor(urlEqualTo("/session")));
        } finally {
            shortClient.close();
        }
    }

    @Test
    void http500_isNotRetried() {
        stubFor(post(urlEqualTo("/session"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        assertThatThrownBy(() -> client.createSession("run-x"))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                        .isEqualTo(TaskExecutionException.Cause.PROVIDER_ERROR));

        verify(1, postRequestedFor(urlEqualTo("/session")));
    }

    @Test
    void retry_backsOffAtLeastOneSecond() {
        stubFor(post(urlEqualTo("/session"))
                .inScenario("backoff")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
                .willSetStateTo("retried"));
        stubFor(post(urlEqualTo("/session"))
                .inScenario("backoff")
                .whenScenarioStateIs("retried")
                .willReturn(aResponse().withStatus(200).withBody("{\"id\":\"sess-1\"}")));

        long start = System.nanoTime();
        String sessionId = client.createSession("run-1");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(sessionId).isEqualTo("sess-1");
        assertThat(elapsedMs).as("one retry must wait ~1s backoff").isGreaterThanOrEqualTo(800);
    }

    @Test
    void isHealthy_doesNotRetryOnReset() {
        stubFor(get(urlEqualTo("/global/health"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        long start = System.nanoTime();
        assertThat(client.isHealthy()).isFalse();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // The probe must fail fast: the backoff retry path would add 1s + 4s of
        // delay, whereas the no-retry path fails in milliseconds. The JDK
        // HttpClient may internally retry an idempotent GET once on a reset, so
        // up to 2 wire requests are acceptable (the retry path would send 6).
        assertThat(elapsedMs).as("health probe must not apply backoff retry").isLessThan(2000);
        assertThat(findAll(getRequestedFor(urlEqualTo("/global/health"))).size())
                .as("health probe must not apply backoff retry").isLessThanOrEqualTo(2);
    }
}
