package io.aria.conductor.execution.runtime.progress;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.aria.conductor.common.event.RunProgressEvent;
import io.aria.conductor.execution.adk.opencode.OpenCodeHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The progress pump polls {@code GET /session/:id/message}, diffs new parts,
 * coalesces consecutive thinking, degrades on failures and never kills the
 * prompt path.
 */
@WireMockTest
class CoreProgressPumpTest {

    private static final Duration POLL = Duration.ofMillis(20);
    private static final Duration COALESCE = Duration.ofMillis(300);
    private static final String PATH = "/session/sess-1/message";

    private OpenCodeHttpClient client;
    private CoreProgressPump pump;
    private final List<CoreProgressPump.ProgressFragment> events = new CopyOnWriteArrayList<>();
    private final UUID runId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();

    @BeforeEach
    void setUp(WireMockRuntimeInfo wm) {
        client = new OpenCodeHttpClient(wm.getHttpBaseUrl(), Duration.ofSeconds(5));
        events.clear();
    }

    @AfterEach
    void tearDown() {
        if (pump != null) {
            pump.stop();
        }
        client.close();
    }

    private void startPump() {
        pump = new CoreProgressPump(client, "sess-1", runId, agentId, events::add, POLL, COALESCE);
        pump.start();
    }

    private static String body(String partsJson) {
        return "[ { \"info\": { \"id\": \"m1\" }, \"parts\": [ " + partsJson + " ] } ]";
    }

    private static int requestsToThePumpPath() {
        return findAll(getRequestedFor(urlEqualTo(PATH))).size();
    }

    @Test
    void diffsOnlyNewPartsAcrossPolls() {
        stubFor(get(urlEqualTo(PATH))
                .inScenario("diff")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"p1\", \"type\": \"reasoning\", \"text\": \"first\" }," +
                        "{ \"id\": \"p2\", \"type\": \"tool\", \"tool\": \"bash\" }")))
                .willSetStateTo("second"));
        stubFor(get(urlEqualTo(PATH))
                .inScenario("diff")
                .whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"p1\", \"type\": \"reasoning\", \"text\": \"first\" }," +
                        "{ \"id\": \"p2\", \"type\": \"tool\", \"tool\": \"bash\" }," +
                        "{ \"id\": \"p3\", \"type\": \"text\", \"text\": \"scheduler found\" }"))));

        startPump();
        await().atMost(Duration.ofSeconds(3)).until(() -> events.size() >= 3);
        pump.stop();

        // p1 (thinking) + p2 (tool) from the first poll, p3 from the second —
        // p1/p2 are never re-emitted.
        assertThat(events).hasSize(3);
        assertThat(events.get(0).kind()).isEqualTo(RunProgressEvent.Kind.THINKING);
        assertThat(events.get(0).content()).isEqualTo("first");
        assertThat(events.get(0).toolName()).isNull();
        assertThat(events.get(1).kind()).isEqualTo(RunProgressEvent.Kind.TOOL_CALL);
        assertThat(events.get(1).toolName()).isEqualTo("bash");
        assertThat(events.get(2).kind()).isEqualTo(RunProgressEvent.Kind.THINKING);
        assertThat(events.get(2).content()).isEqualTo("scheduler found");
        // Monotonic seq for client dedupe, starting at one.
        assertThat(events.get(0).seq()).isEqualTo(1);
        assertThat(events.get(1).seq()).isEqualTo(2);
        assertThat(events.get(2).seq()).isEqualTo(3);
        assertThat(events).allSatisfy(e -> assertThat(e.iteration()).isZero());
    }

    @Test
    void coalescesConsecutiveThinkingPartsIntoOneEvent() {
        stubFor(get(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"a\", \"type\": \"reasoning\", \"text\": \"t1\" }," +
                        "{ \"id\": \"b\", \"type\": \"reasoning\", \"text\": \"t2\" }," +
                        "{ \"id\": \"c\", \"type\": \"reasoning\", \"text\": \"t3\" }," +
                        "{ \"id\": \"d\", \"type\": \"reasoning\", \"text\": \"t4\" }," +
                        "{ \"id\": \"e\", \"type\": \"reasoning\", \"text\": \"t5\" }"))));

        startPump();
        await().atMost(Duration.ofSeconds(3)).until(() -> events.size() >= 1);
        pump.stop();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).kind()).isEqualTo(RunProgressEvent.Kind.THINKING);
        assertThat(events.get(0).content()).isEqualTo("t5");
        assertThat(events.get(0).seq()).isEqualTo(1);
    }

    @Test
    void degradedPollsDoNotResetTheWatermarkOrReplayParts() {
        stubFor(get(urlEqualTo(PATH))
                .inScenario("degrade")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"p1\", \"type\": \"text\", \"text\": \"one\" }")))
                .willSetStateTo("fail"));
        stubFor(get(urlEqualTo(PATH))
                .inScenario("degrade")
                .whenScenarioStateIs("fail")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("same"));
        stubFor(get(urlEqualTo(PATH))
                .inScenario("degrade")
                .whenScenarioStateIs("same")
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"p1\", \"type\": \"text\", \"text\": \"one\" }"))));

        startPump();
        await().atMost(Duration.ofSeconds(3)).until(() -> events.size() >= 1);
        // Poll 2 is the 500. Poll 4 is only sent after poll 3's (200, unchanged)
        // response has been diffed, so observing it proves the degraded poll ran
        // and the unchanged snapshot did not replay.
        await().atMost(Duration.ofSeconds(3)).until(() -> requestsToThePumpPath() >= 4);
        pump.stop();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).content()).isEqualTo("one");
        assertThat(events.get(0).seq()).isEqualTo(1);
    }

    @Test
    void neverCoalescesToolEvents() {
        stubFor(get(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"a\", \"type\": \"tool\", \"tool\": \"read\" }," +
                        "{ \"id\": \"b\", \"type\": \"tool\", \"tool\": \"bash\" }"))));

        startPump();
        await().atMost(Duration.ofSeconds(3)).until(() -> events.size() >= 2);
        pump.stop();

        assertThat(events).hasSize(2);
        assertThat(events).allSatisfy(e -> assertThat(e.kind()).isEqualTo(RunProgressEvent.Kind.TOOL_CALL));
        assertThat(events.get(0).toolName()).isEqualTo("read");
        assertThat(events.get(1).toolName()).isEqualTo("bash");
        assertThat(events.get(0).seq()).isEqualTo(1);
        assertThat(events.get(1).seq()).isEqualTo(2);
    }

    @Test
    void mapsACompletedToolPartToAToolResultWithItsStateOutput() {
        stubFor(get(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"a\", \"type\": \"tool\", \"tool\": \"bash\","
                                + " \"state\": { \"status\": \"completed\", \"output\": \"all green\" } }"))));

        startPump();
        await().atMost(Duration.ofSeconds(3)).until(() -> events.size() >= 1);
        pump.stop();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).kind()).isEqualTo(RunProgressEvent.Kind.TOOL_RESULT);
        assertThat(events.get(0).content()).isEqualTo("all green");
        assertThat(events.get(0).toolName()).isEqualTo("bash");
        assertThat(events.get(0).seq()).isEqualTo(1);
    }

    @Test
    void degradesOnServerErrorsWithoutThrowingOrEmitting() {
        stubFor(get(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(500)));

        startPump();
        // Two served polls prove the 500 response really went through the
        // degradation path instead of the test asserting on a starved thread.
        await().atMost(Duration.ofSeconds(3)).until(() -> requestsToThePumpPath() >= 2);
        pump.stop();

        assertThat(events).isEmpty();
    }

    @Test
    void sinkExceptionsDoNotKillThePump() {
        stubFor(get(urlEqualTo(PATH))
                .inScenario("sink")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"a\", \"type\": \"tool\", \"tool\": \"read\" }")))
                .willSetStateTo("second"));
        stubFor(get(urlEqualTo(PATH))
                .inScenario("sink")
                .whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"a\", \"type\": \"tool\", \"tool\": \"read\" }," +
                        "{ \"id\": \"b\", \"type\": \"tool\", \"tool\": \"bash\" }"))));

        List<String> seen = new CopyOnWriteArrayList<>();
        pump = new CoreProgressPump(client, "sess-1", runId, agentId, fragment -> {
            if (seen.isEmpty()) {
                seen.add("boom");
                throw new IllegalStateException("sink down");
            }
            seen.add(fragment.toolName());
        }, POLL, COALESCE);
        pump.start();
        await().atMost(Duration.ofSeconds(3)).until(() -> seen.contains("bash"));
        pump.stop();

        // The first event threw inside the sink; the pump survived and
        // delivered the second.
        assertThat(seen).containsExactly("boom", "bash");
    }

    @Test
    void startIsIdempotent() {
        stubFor(get(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200)
                        .withFixedDelay(200)
                        .withBody(body("{ \"id\": \"a\", \"type\": \"tool\", \"tool\": \"read\" }"))));

        pump = new CoreProgressPump(client, "sess-1", runId, agentId, events::add,
                Duration.ofSeconds(1), COALESCE);
        pump.start();
        pump.start(); // idempotent: a second loop would have issued its own immediate GET
        await().atMost(Duration.ofSeconds(3)).until(() -> events.size() >= 1);

        assertThat(requestsToThePumpPath()).isEqualTo(1);
        assertThat(events).hasSize(1);
        pump.stop();
        pump.stop();
    }

    @Test
    void resetReplayCapsAtTheLatestTenParts() {
        pump = new CoreProgressPump(client, "sess-1", runId, agentId, events::add);
        // Three messages seen before; the server now reports only two (a real
        // shrink), so the reset path replays from the start of the new snapshot.
        List<Integer> watermark = new ArrayList<>(List.of(0, 0, 0));
        List<OpenCodeHttpClient.MessageSnapshot> shrunk = List.of(
                message("m1", 7), message("m2", 7));

        List<CoreProgressPump.ProgressFragment> fragments = pump.diff(shrunk, watermark);

        // 14 new parts (7 + 7) after the shrink; only the latest 10 survive.
        assertThat(fragments).hasSize(10);
        assertThat(fragments).extracting(CoreProgressPump.ProgressFragment::content)
                .containsExactly("m1-p4", "m1-p5", "m1-p6",
                        "m2-p0", "m2-p1", "m2-p2", "m2-p3", "m2-p4", "m2-p5", "m2-p6");
        assertThat(fragments).extracting(CoreProgressPump.ProgressFragment::seq)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        // The watermark reflects the whole shrunk snapshot, not just the replayed tail.
        assertThat(watermark).containsExactly(7, 7);
    }

    private static OpenCodeHttpClient.MessageSnapshot message(String id, int partCount) {
        List<OpenCodeHttpClient.PartSnapshot> parts = new ArrayList<>();
        for (int i = 0; i < partCount; i++) {
            // error parts carry their text as content and are never coalesced,
            // so the cap assertion can name every surviving fragment exactly.
            parts.add(new OpenCodeHttpClient.PartSnapshot(id + "-p" + i, "error",
                    id + "-p" + i, null, null, null));
        }
        return new OpenCodeHttpClient.MessageSnapshot(id, parts);
    }

    @Test
    void stopIsIdempotentAndHaltsEmission() {
        stubFor(get(urlEqualTo(PATH))
                .inScenario("stop")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"a\", \"type\": \"tool\", \"tool\": \"read\" }")))
                .willSetStateTo("second"));
        stubFor(get(urlEqualTo(PATH))
                .inScenario("stop")
                .whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"a\", \"type\": \"tool\", \"tool\": \"read\" }," +
                        "{ \"id\": \"b\", \"type\": \"tool\", \"tool\": \"bash\" }"))));

        pump = new CoreProgressPump(client, "sess-1", runId, agentId, events::add,
                Duration.ofSeconds(2), COALESCE);
        pump.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> events.size() >= 1);
        pump.stop();
        pump.stop(); // idempotent

        // A still-running pump would poll the second scenario state (after the
        // two-second interval) and emit the new part; the halted pump must not,
        // even for an in-flight poll.
        await().during(Duration.ofMillis(2500)).atMost(Duration.ofSeconds(4))
                .untilAsserted(() -> assertThat(events).hasSize(1));
        assertThat(events.get(0).seq()).isEqualTo(1);
    }

    @Test
    void stopInsideTheSinkHaltsTheRestOfTheBatch() {
        stubFor(get(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"a\", \"type\": \"tool\", \"tool\": \"read\" }," +
                        "{ \"id\": \"b\", \"type\": \"tool\", \"tool\": \"bash\" }"))));

        pump = new CoreProgressPump(client, "sess-1", runId, agentId, fragment -> {
            events.add(fragment);
            pump.stop();
        }, POLL, COALESCE);
        pump.start();
        await().atMost(Duration.ofSeconds(3)).until(() -> !events.isEmpty());
        pump.stop();

        // The second fragment of the batch is never emitted after stop().
        assertThat(events).hasSize(1);
        assertThat(events.get(0).toolName()).isEqualTo("read");
    }
}
