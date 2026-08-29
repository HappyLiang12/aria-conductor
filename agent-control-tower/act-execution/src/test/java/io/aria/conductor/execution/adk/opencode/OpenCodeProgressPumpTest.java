package io.aria.conductor.execution.adk.opencode;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.aria.conductor.common.event.RunProgressEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * S9: OpenCodeProgressPump — polls {@code GET /session/:id/message}, diffs new
 * parts, coalesces consecutive thinking, degrades on failures, never kills the
 * task path.
 */
@WireMockTest
class OpenCodeProgressPumpTest {

    private static final Duration POLL = Duration.ofMillis(20);
    private static final Duration COALESCE = Duration.ofMillis(300);

    private OpenCodeHttpClient client;
    private OpenCodeProgressPump pump;
    private final List<RunProgressEvent> events = new CopyOnWriteArrayList<>();
    private final UUID runId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();

    @BeforeEach
    void setUp(WireMockRuntimeInfo wm) {
        client = new OpenCodeHttpClient(wm.getHttpBaseUrl(), Duration.ofSeconds(5));
        events.clear();
    }

    @AfterEach
    void tearDown() {
        if (pump != null) pump.stop();
        client.close();
    }

    private void startPump() {
        pump = new OpenCodeProgressPump(client, "sess-1", runId, agentId, events::add, POLL, COALESCE);
        pump.start();
    }

    private static String body(String partsJson) {
        return "[ { \"info\": { \"id\": \"m1\" }, \"parts\": [ " + partsJson + " ] } ]";
    }

    @Test
    void diffsOnlyNewPartsAcrossPolls() throws Exception {
        stubFor(get(urlEqualTo("/session/sess-1/message"))
                .inScenario("diff")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"p1\", \"type\": \"reasoning\", \"text\": \"first\" }," +
                        "{ \"id\": \"p2\", \"type\": \"tool\", \"tool\": \"bash\" }")))
                .willSetStateTo("second"));
        stubFor(get(urlEqualTo("/session/sess-1/message"))
                .inScenario("diff")
                .whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"p1\", \"type\": \"reasoning\", \"text\": \"first\" }," +
                        "{ \"id\": \"p2\", \"type\": \"tool\", \"tool\": \"bash\" }," +
                        "{ \"id\": \"p3\", \"type\": \"text\", \"text\": \"scheduler found\" }"))));

        startPump();
        await().atMost(java.time.Duration.ofSeconds(3)).until(() -> events.size() >= 3);
        pump.stop();

        // p1 (thinking) + p2 (tool) from first poll, p3 from second — p1/p2 never re-emitted.
        assertThat(events).hasSize(3);
        assertThat(events.get(0).getKind()).isEqualTo(RunProgressEvent.Kind.THINKING);
        assertThat(events.get(1).getKind()).isEqualTo(RunProgressEvent.Kind.TOOL_CALL);
        assertThat(events.get(1).getToolName()).isEqualTo("bash");
        assertThat(events.get(2).getContent()).isEqualTo("scheduler found");
        assertThat(events).allSatisfy(e -> {
            assertThat(e.getRunId()).isEqualTo(runId);
            assertThat(e.getAgentId()).isEqualTo(agentId);
        });
        // monotonic seq for client dedupe
        assertThat(events.get(0).getSeq()).isLessThan(events.get(2).getSeq());
    }

    @Test
    void coalescesConsecutiveThinkingPartsIntoOneEvent() throws Exception {
        stubFor(get(urlEqualTo("/session/sess-1/message"))
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"a\", \"type\": \"reasoning\", \"text\": \"t1\" }," +
                        "{ \"id\": \"b\", \"type\": \"reasoning\", \"text\": \"t2\" }," +
                        "{ \"id\": \"c\", \"type\": \"reasoning\", \"text\": \"t3\" }," +
                        "{ \"id\": \"d\", \"type\": \"reasoning\", \"text\": \"t4\" }," +
                        "{ \"id\": \"e\", \"type\": \"reasoning\", \"text\": \"t5\" }"))));

        startPump();
        await().atMost(java.time.Duration.ofSeconds(3)).until(() -> events.size() >= 1);
        pump.stop();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getKind()).isEqualTo(RunProgressEvent.Kind.THINKING);
        assertThat(events.get(0).getContent()).isEqualTo("t5");
    }

    @Test
    void neverCoalescesToolEvents() throws Exception {
        stubFor(get(urlEqualTo("/session/sess-1/message"))
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"a\", \"type\": \"tool\", \"tool\": \"read\" }," +
                        "{ \"id\": \"b\", \"type\": \"tool\", \"tool\": \"bash\" }"))));

        startPump();
        await().atMost(java.time.Duration.ofSeconds(3)).until(() -> events.size() >= 2);
        pump.stop();

        assertThat(events).hasSize(2);
        assertThat(events).allSatisfy(e -> assertThat(e.getKind()).isEqualTo(RunProgressEvent.Kind.TOOL_CALL));
    }

    @Test
    void degradesOnServerErrorsWithoutThrowingOrEmitting() throws Exception {
        stubFor(get(urlEqualTo("/session/sess-1/message"))
                .willReturn(aResponse().withStatus(500)));

        startPump();
        Thread.sleep(150);
        pump.stop();

        assertThat(events).isEmpty();
    }

    @Test
    void sinkExceptionsDoNotKillThePump() throws Exception {
        stubFor(get(urlEqualTo("/session/sess-1/message"))
                .inScenario("sink")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"a\", \"type\": \"tool\", \"tool\": \"read\" }")))
                .willSetStateTo("second"));
        stubFor(get(urlEqualTo("/session/sess-1/message"))
                .inScenario("sink")
                .whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"a\", \"type\": \"tool\", \"tool\": \"read\" }," +
                        "{ \"id\": \"b\", \"type\": \"tool\", \"tool\": \"bash\" }"))));

        List<String> seen = new CopyOnWriteArrayList<>();
        pump = new OpenCodeProgressPump(client, "sess-1", runId, agentId, ev -> {
            if (seen.isEmpty()) {
                seen.add("boom");
                throw new IllegalStateException("sink down");
            }
            seen.add(ev.getToolName());
        }, POLL, COALESCE);
        pump.start();
        await().atMost(java.time.Duration.ofSeconds(3)).until(() -> seen.contains("bash"));
        pump.stop();

        // first event threw inside the sink; the pump survived and delivered the second.
        assertThat(seen).containsExactly("boom", "bash");
    }

    @Test
    void stopIsIdempotentAndHaltsEmission() throws Exception {
        stubFor(get(urlEqualTo("/session/sess-1/message"))
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"a\", \"type\": \"tool\", \"tool\": \"read\" }"))));

        startPump();
        await().atMost(java.time.Duration.ofSeconds(3)).until(() -> events.size() >= 1);
        pump.stop();
        int after = events.size();
        pump.stop(); // idempotent
        Thread.sleep(120);

        assertThat(events.size()).isEqualTo(after);
    }
}
