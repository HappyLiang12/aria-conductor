package io.aria.conductor.execution.runtime.progress;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.aria.conductor.common.event.RunProgressEvent;
import io.aria.conductor.execution.adk.opencode.OpenCodeHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The progress pump polls {@code GET /session/:id/message}, diffs new parts,
 * coalesces consecutive thinking, degrades on failures and never kills the
 * prompt path.
 *
 * <p>The degradation tests additionally assert the operator-visible stall
 * signal: a sustained degraded streak logs exactly one WARN and increments a
 * countable signal, and a successful poll re-arms it.
 */
@WireMockTest
class CoreProgressPumpTest {

    private static final Duration POLL = Duration.ofMillis(20);
    private static final Duration COALESCE = Duration.ofMillis(300);
    private static final String PATH = "/session/sess-1/message";

    private OpenCodeHttpClient client;
    private CoreProgressPump pump;
    private ListAppender<ILoggingEvent> pumpLogs;
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
        if (pumpLogs != null) {
            pumpLogger().detachAppender(pumpLogs);
            pumpLogs.stop();
            pumpLogs = null;
        }
    }

    private void startPump() {
        pump = new CoreProgressPump(client, "sess-1", runId, agentId, events::add, POLL, COALESCE);
        pump.start();
    }

    /**
     * One assistant message of parts: {@code info.role} is on the real wire for
     * every message (the committed fixture peer answers it too), so the shared
     * fixture carries it and the pump's role rule is exercised by every test.
     */
    private static String body(String partsJson) {
        return "[ " + messageWithRole("assistant", partsJson) + " ]";
    }

    /** One {@code {info, parts}} entry of the recorded message-list shape. */
    private static String messageWithRole(String role, String partsJson) {
        return "{ \"info\": { \"id\": \"m\", \"role\": \"" + role + "\" }, \"parts\": [ "
                + partsJson + " ] }";
    }

    private static int requestsToThePumpPath() {
        return findAll(getRequestedFor(urlEqualTo(PATH))).size();
    }

    // ---- M6: the emission lock must not span the sink ----

    /**
     * M6: the sink is a synchronous persistence write on the pump thread. It must
     * not serialize the halt: with the emission guard narrowed to the flag check,
     * a stuck insert costs at most the stop join budget instead of holding the
     * prompt's terminal completion behind it.
     */
    @Test
    void aSlowSinkDoesNotDelayStopBeyondTheJoinBudget() throws Exception {
        stubFor(get(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"a\", \"type\": \"tool\", \"tool\": \"read\" },"
                                + "{ \"id\": \"b\", \"type\": \"tool\", \"tool\": \"bash\" }"))));
        CountDownLatch sinkEntered = new CountDownLatch(1);
        CountDownLatch releaseSink = new CountDownLatch(1);
        AtomicBoolean sinkReturned = new AtomicBoolean();
        pump = new CoreProgressPump(client, "sess-1", runId, agentId, fragment -> {
            events.add(fragment);
            sinkEntered.countDown();
            awaitUninterruptibly(releaseSink, Duration.ofSeconds(20));
            sinkReturned.set(true);
        }, POLL, COALESCE, new CoreProgressPump.ProgressCursor(), Duration.ofMillis(150));
        pump.start();
        assertThat(sinkEntered.await(5, TimeUnit.SECONDS))
                .as("the pump is inside the persistence call")
                .isTrue();

        ExecutorService stopper = Executors.newSingleThreadExecutor();
        try {
            Future<?> stopping = stopper.submit(pump::stop);
            stopping.get(3, TimeUnit.SECONDS);
            assertThat(sinkReturned)
                    .as("stop() returned while the persistence call was still in flight")
                    .isFalse();

            releaseSink.countDown();
            // The rest of that batch is still dropped: the halt re-checks the flag
            // before every fragment.
            await().during(Duration.ofMillis(400)).atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(events).hasSize(1));
        } finally {
            releaseSink.countDown();
            stopper.shutdownNow();
        }
    }

    /**
     * M6's ordering premise, pinned: one single-threaded emitter (the pump worker)
     * hands fragments out in cursor order, so narrowing the lock cannot reorder a
     * run's progress.
     */
    @Test
    void everyFragmentIsEmittedByOneThreadInStrictSeqOrder() {
        stubFor(get(urlEqualTo(PATH))
                .inScenario("order")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"a\", \"type\": \"tool\", \"tool\": \"read\" },"
                                + "{ \"id\": \"b\", \"type\": \"tool\", \"tool\": \"bash\" }")))
                .willSetStateTo("second"));
        stubFor(get(urlEqualTo(PATH))
                .inScenario("order")
                .whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"a\", \"type\": \"tool\", \"tool\": \"read\" },"
                                + "{ \"id\": \"b\", \"type\": \"tool\", \"tool\": \"bash\" },"
                                + "{ \"id\": \"c\", \"type\": \"tool\", \"tool\": \"grep\" }"))));

        List<String> emitterThreads = new CopyOnWriteArrayList<>();
        pump = new CoreProgressPump(client, "sess-1", runId, agentId, fragment -> {
            events.add(fragment);
            emitterThreads.add(Thread.currentThread().getName());
        }, POLL, COALESCE);
        pump.start();
        await().atMost(Duration.ofSeconds(3)).until(() -> events.size() >= 3);
        pump.stop();

        assertThat(events).extracting(CoreProgressPump.ProgressFragment::seq)
                .containsExactly(1L, 2L, 3L);
        assertThat(emitterThreads).containsOnly("oc-progress-pump-" + runId + "-" + agentId);
    }

    /**
     * A latch wait that survives an interrupt: it models the section where the
     * interrupt cannot undo work already in flight -- a message-list response
     * that already arrived, or a watermark diff that is already running.
     */
    private static void awaitUninterruptibly(CountDownLatch latch, Duration budget) {
        Instant deadline = Instant.now().plus(budget);
        boolean interrupted = false;
        while (Instant.now().isBefore(deadline)) {
            try {
                if (latch.await(50, TimeUnit.MILLISECONDS)) {
                    break;
                }
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static OpenCodeHttpClient.MessageListResult oneNewPart() {
        return new OpenCodeHttpClient.MessageListResult(List.of(message("m1", 1)), false);
    }

    /** The pump worker is inside the client call, and the response is held there. */
    private OpenCodeHttpClient blockedPollClient(CountDownLatch pollEntered, CountDownLatch releasePoll)
            throws Exception {
        OpenCodeHttpClient blocked = mock(OpenCodeHttpClient.class);
        when(blocked.listMessages("sess-1")).thenAnswer(invocation -> {
            pollEntered.countDown();
            awaitUninterruptibly(releasePoll, Duration.ofSeconds(20));
            return oneNewPart();
        });
        return blocked;
    }

    /**
     * M5: {@code stop()} is the halt the prompt path waits on, so it must not
     * return while the poll worker is still inside a section that mutates the
     * session cursor. A slow sink can no longer hold the emission lock (M6), so
     * the join is what keeps "no cursor mutation after stop() returns" true.
     */
    @Test
    void stopWaitsForThePollWorkerToExitBeforeReturning() throws Exception {
        CountDownLatch pollEntered = new CountDownLatch(1);
        CountDownLatch releasePoll = new CountDownLatch(1);
        pump = new CoreProgressPump(blockedPollClient(pollEntered, releasePoll), "sess-1", runId, agentId,
                events::add, POLL, COALESCE, new CoreProgressPump.ProgressCursor(),
                Duration.ofSeconds(30));
        pump.start();
        assertThat(pollEntered.await(3, TimeUnit.SECONDS)).isTrue();

        ExecutorService stopper = Executors.newSingleThreadExecutor();
        try {
            Future<?> stopping = stopper.submit(pump::stop);
            assertThatThrownBy(() -> stopping.get(300, TimeUnit.MILLISECONDS))
                    .as("stop() must not return while the poll worker is still running")
                    .isInstanceOf(TimeoutException.class);

            releasePoll.countDown();
            stopping.get(10, TimeUnit.SECONDS);
            assertThat(events).isEmpty();
        } finally {
            releasePoll.countDown();
            stopper.shutdownNow();
        }
    }

    /**
     * M5's bounded side: when the worker does not exit within the stop budget,
     * the abandoned poll still may not consume the session cursor. Otherwise the
     * parts it read but never emitted would be marked seen and silently lost for
     * every later prompt of the session.
     */
    @Test
    void aPollThatOutlivesTheStopBudgetNeitherEmitsNorAdvancesTheCursor() throws Exception {
        CountDownLatch pollEntered = new CountDownLatch(1);
        CountDownLatch releasePoll = new CountDownLatch(1);
        CoreProgressPump.ProgressCursor cursor = new CoreProgressPump.ProgressCursor();
        pump = new CoreProgressPump(blockedPollClient(pollEntered, releasePoll), "sess-1", runId, agentId,
                events::add, POLL, COALESCE, cursor, Duration.ofMillis(150));
        pump.start();
        assertThat(pollEntered.await(3, TimeUnit.SECONDS)).isTrue();

        // The budget expires with the worker still inside the client call.
        pump.stop();
        releasePoll.countDown();

        await().during(Duration.ofMillis(400)).atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(events).isEmpty());
        assertThat(cursor.sequenceValue())
                .as("an abandoned poll must not consume seq")
                .isZero();
        assertThat(cursor.watermarkSize())
                .as("an abandoned poll must not mark parts as already streamed")
                .isZero();
        assertThat(pump.diff(List.of(message("m1", 1))))
                .as("the part the abandoned poll read is still streamable")
                .hasSize(1);
    }

    /**
     * M5: the watermark is shared session state. A second prompt's pre-prompt
     * seed must not interleave with a diff that is still running: the two writers
     * share the cursor monitor, so the seed either lands fully before or fully
     * after the diff.
     */
    @Test
    void theBaselineSeedCannotInterleaveWithAnInFlightDiff() throws Exception {
        CoreProgressPump.ProgressCursor cursor = new CoreProgressPump.ProgressCursor();
        cursor.seedFrom(List.of(message("m1", 2)));
        pump = new CoreProgressPump(client, "sess-1", runId, agentId, events::add, POLL, COALESCE, cursor);

        GatedSnapshots inFlight = new GatedSnapshots(message("m1", 3));
        AtomicReference<List<CoreProgressPump.ProgressFragment>> diffed = new AtomicReference<>();
        Thread differ = Thread.ofVirtual().start(() -> diffed.set(pump.diff(inFlight)));
        assertThat(inFlight.entered.await(3, TimeUnit.SECONDS))
                .as("the diff is inside the cursor section")
                .isTrue();

        AtomicBoolean seeded = new AtomicBoolean();
        Thread seeder = Thread.ofVirtual()
                .start(() -> {
                    cursor.seedFrom(List.of(message("m1", 1)));
                    seeded.set(true);
                });
        try {
            await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(seeded).isFalse());
        } finally {
            inFlight.release.countDown();
            differ.join(5000);
            seeder.join(5000);
        }

        // The diff ran against the watermark it started with ([2]) and the seed
        // applied atomically afterwards: exactly the one part newer than [2] was
        // emitted, and the watermark is the single-message seed, not a hybrid.
        assertThat(diffed.get()).hasSize(1);
        assertThat(diffed.get().get(0).seq()).isEqualTo(1L);
        assertThat(cursor.watermarkSize()).isEqualTo(1);
    }

    /** A snapshot list whose first read blocks, so a diff can be held mid-section. */
    private static final class GatedSnapshots extends ArrayList<OpenCodeHttpClient.MessageSnapshot> {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        GatedSnapshots(OpenCodeHttpClient.MessageSnapshot snapshot) {
            super.add(snapshot);
        }

        @Override
        public OpenCodeHttpClient.MessageSnapshot get(int index) {
            if (index == 0) {
                entered.countDown();
                awaitUninterruptibly(release, Duration.ofSeconds(20));
            }
            return super.get(index);
        }
    }

    /**
     * #103 defect 1 (live HOST evidence, run a25f48f5 seq 1): the operator's own
     * prompt came back labelled THINKING, because {@code listMessages} never read
     * {@code info.role} and opencode lists the user message too. Only assistant
     * activity is progress: a user-role text part never produces a fragment,
     * while the same session's assistant parts do. The first served poll sees
     * only the user message (the turn's assistant message is not listed yet) --
     * exactly the live shape that echoed the prompt.
     */
    @Test
    void aUserMessageIsNeverStreamedWhileTheAssistantMessageOfTheSameSessionIs() {
        String operatorPrompt = "Write a detailed 900-word essay about distributed consensus algorithms.";
        stubFor(get(urlEqualTo(PATH))
                .inScenario("role")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200).withBody(
                        "[ " + messageWithRole("user",
                                "{ \"id\": \"up\", \"type\": \"text\", \"text\": \"" + operatorPrompt + "\" }")
                                + " ]"))
                .willSetStateTo("assistant"));
        stubFor(get(urlEqualTo(PATH))
                .inScenario("role")
                .whenScenarioStateIs("assistant")
                .willReturn(aResponse().withStatus(200).withBody(
                        "[ " + messageWithRole("user",
                                "{ \"id\": \"up\", \"type\": \"text\", \"text\": \"" + operatorPrompt + "\" }")
                                + ", " + messageWithRole("assistant",
                                "{ \"id\": \"ar\", \"type\": \"reasoning\", \"text\": \"outlining the essay\" }")
                                + " ]")));

        startPump();
        // Poll 3 is only issued after poll 2's batch has been diffed and emitted,
        // so seeing it proves both responses went through the diff.
        await().atMost(Duration.ofSeconds(3)).until(() -> requestsToThePumpPath() >= 3);
        pump.stop();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).kind()).isEqualTo(RunProgressEvent.Kind.THINKING);
        assertThat(events.get(0).content()).isEqualTo("outlining the essay");
        assertThat(events).extracting(CoreProgressPump.ProgressFragment::content)
                .as("the operator's own prompt is not the assistant's thinking")
                .doesNotContain(operatorPrompt);
    }

    /**
     * #103 defect 2 (live evidence seq 3): an empty reasoning part was emitted as
     * an empty THINKING row. A fragment with no content says nothing to the
     * operator, so it is never streamed.
     */
    @Test
    void anEmptyReasoningPartIsNeverStreamedAsAProgressFragment() {
        stubFor(get(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"a\", \"type\": \"reasoning\", \"text\": \"\" }"))));

        startPump();
        await().atMost(Duration.ofSeconds(3)).until(() -> requestsToThePumpPath() >= 2);
        pump.stop();

        assertThat(events)
                .as("a blank reasoning fragment carries nothing an operator can read")
                .isEmpty();
    }

    /**
     * #103 defect 2, other half (live evidence seq 2): the {@code step-start}
     * part-type marker was streamed as a STATUS row whose content was the marker
     * itself. A turn boundary is bookkeeping, not activity: the step markers are
     * skipped and never become a fragment (an unknown part type still surfaces as
     * STATUS, so a core that grows a new part type is not silently invisible).
     */
    @Test
    void stepMarkersAreSkippedInsteadOfStreamedAsStatusRowsOfTheirOwnName() {
        stubFor(get(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"s1\", \"type\": \"step-start\" },"
                                + "{ \"id\": \"r1\", \"type\": \"reasoning\", \"text\": \"outlining the essay\" },"
                                + "{ \"id\": \"s2\", \"type\": \"step-finish\" }"))));

        startPump();
        await().atMost(Duration.ofSeconds(3)).until(() -> requestsToThePumpPath() >= 2);
        pump.stop();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).kind()).isEqualTo(RunProgressEvent.Kind.THINKING);
        assertThat(events.get(0).content()).isEqualTo("outlining the essay");
        assertThat(events).extracting(CoreProgressPump.ProgressFragment::content)
                .doesNotContain("step-start", "step-finish");
        // A dropped part burns no seq: the surviving fragment is still the first.
        assertThat(events.get(0).seq()).isEqualTo(1);
    }

    /**
     * #103 defect 3: {@code case "reasoning", "text" -> THINKING} merged the
     * model's answer into its thinking, so the drawer showed the reply under the
     * think affordance. The assistant's text part is its own kind of activity.
     */
    @Test
    void anAssistantTextPartIsStreamedAsItsOwnKindAndNotAsThinking() {
        stubFor(get(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"t1\", \"type\": \"text\", \"text\": \"Consensus needs a majority.\" }"))));

        startPump();
        await().atMost(Duration.ofSeconds(3)).until(() -> !events.isEmpty());
        pump.stop();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).content()).isEqualTo("Consensus needs a majority.");
        assertThat(events.get(0).kind())
                .as("the model's answer is its own kind, not THINKING")
                .isEqualTo(RunProgressEvent.Kind.ANSWER);
        assertThat(events.get(0).seq()).isEqualTo(1);
        assertThat(events.get(0).toolName()).isNull();
    }

    /**
     * The #103 mapping table, pinned in one poll: reasoning is THINKING, text is
     * ANSWER, a tool is TOOL_CALL/TOOL_RESULT, the turn markers are skipped and an
     * unknown part type still surfaces as STATUS. Consecutive THINKING still
     * coalesces; consecutive ANSWER never does, so no answer text is lost.
     */
    @Test
    void mapsEachAssistantPartTypeToItsOwnKindAndSkipsTheTurnMarkers() {
        stubFor(get(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"s1\", \"type\": \"step-start\" },"
                                + "{ \"id\": \"r1\", \"type\": \"reasoning\", \"text\": \"thinking one\" },"
                                + "{ \"id\": \"r2\", \"type\": \"reasoning\", \"text\": \"thinking two\" },"
                                + "{ \"id\": \"t1\", \"type\": \"text\", \"text\": \"answer one\" },"
                                + "{ \"id\": \"t2\", \"type\": \"text\", \"text\": \"answer two\" },"
                                + "{ \"id\": \"x1\", \"type\": \"patch\" },"
                                + "{ \"id\": \"s2\", \"type\": \"step-finish\" }"))));

        startPump();
        await().atMost(Duration.ofSeconds(3)).until(() -> events.size() >= 4);
        pump.stop();

        assertThat(events).hasSize(4);
        assertThat(events.get(0).kind()).isEqualTo(RunProgressEvent.Kind.THINKING);
        assertThat(events.get(0).content()).isEqualTo("thinking two");
        assertThat(events.get(1).kind()).isEqualTo(RunProgressEvent.Kind.ANSWER);
        assertThat(events.get(1).content()).isEqualTo("answer one");
        assertThat(events.get(2).kind()).isEqualTo(RunProgressEvent.Kind.ANSWER);
        assertThat(events.get(2).content()).isEqualTo("answer two");
        assertThat(events.get(3).kind()).isEqualTo(RunProgressEvent.Kind.STATUS);
        assertThat(events.get(3).content()).isEqualTo("patch");
        assertThat(events).extracting(CoreProgressPump.ProgressFragment::seq)
                .containsExactly(1L, 2L, 3L, 4L);
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
        // p3 is the model's text part: its answer, not more thinking (#103 defect 3).
        assertThat(events.get(2).kind()).isEqualTo(RunProgressEvent.Kind.ANSWER);
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

    /**
     * I7: a sandbox proxy that answers 502 forever yields zero progress. The
     * stall must become operator-visible: after three consecutive degraded
     * polls exactly one WARN is logged (carrying runId, session and the last
     * cause), and the countable signal stays at one while the stall continues.
     */
    @Test
    void aSustainedDegradedStallWarnsExactlyOnceAndCountsItself() {
        stubFor(get(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(502).withBody("bad gateway from the sandbox proxy")));
        ListAppender<ILoggingEvent> logs = capturePumpLogs();

        startPump();
        await().atMost(Duration.ofSeconds(3)).until(() -> pump.degradedPollCount() >= 4);
        assertThat(pump.degradedStallSignals()).isEqualTo(1);
        assertThat(pump.consecutiveDegradedPolls()).isGreaterThanOrEqualTo(4);

        // The stall keeps polling and keeps counting, but it does not warn again.
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(pump.degradedStallSignals()).isEqualTo(1));
        pump.stop();
        long warns = warnCount(logs);

        assertThat(pump.degradedPollCount()).isGreaterThanOrEqualTo(4);
        assertThat(pump.lastDegradedCause())
                .as("the pump keeps the last cause for the signal surface")
                .contains("502");
        assertThat(warns).isEqualTo(1);
        String warning = firstWarn(logs);
        assertThat(warning)
                .contains(runId.toString())
                .contains("sess-1")
                .contains("502");
    }

    /**
     * I7 recovery half: a successful poll resets the stall state, so a later
     * stall -- after a healthy stretch -- warns again instead of staying silent.
     */
    @Test
    void recoveryReArmsTheStallSignalSoALaterStallWarnsAgain() throws Exception {
        OpenCodeHttpClient flaky = mock(OpenCodeHttpClient.class);
        AtomicBoolean healthy = new AtomicBoolean();
        when(flaky.listMessages("sess-1")).thenAnswer(invocation -> healthy.get()
                ? new OpenCodeHttpClient.MessageListResult(List.of(), false)
                : new OpenCodeHttpClient.MessageListResult(List.of(), true, "HTTP 502"));

        pump = new CoreProgressPump(flaky, "sess-1", runId, agentId, events::add, POLL, COALESCE);
        pump.start();
        await().atMost(Duration.ofSeconds(3)).until(() -> pump.degradedStallSignals() == 1);
        assertThat(pump.consecutiveDegradedPolls()).isGreaterThanOrEqualTo(3);

        healthy.set(true);
        await().atMost(Duration.ofSeconds(3)).until(() -> pump.consecutiveDegradedPolls() == 0);
        assertThat(pump.degradedStallSignals()).isEqualTo(1);

        healthy.set(false);
        await().atMost(Duration.ofSeconds(3)).until(() -> pump.degradedStallSignals() == 2);
        pump.stop();

        // The cumulative degraded-poll counter never resets: it is the countable
        // signal a caller can poll to see that progress has been unreliable.
        assertThat(pump.degradedStallSignals()).isEqualTo(2);
        assertThat(pump.degradedPollCount()).isGreaterThanOrEqualTo(6);
        assertThat(events).isEmpty();
    }

    private ListAppender<ILoggingEvent> capturePumpLogs() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        pumpLogs = appender;
        pumpLogger().addAppender(appender);
        return appender;
    }

    private static Logger pumpLogger() {
        return (Logger) LoggerFactory.getLogger(CoreProgressPump.class);
    }

    private static long warnCount(ListAppender<ILoggingEvent> logs) {
        return logs.list.stream().filter(event -> event.getLevel() == Level.WARN).count();
    }

    private static String firstWarn(ListAppender<ILoggingEvent> logs) {
        return logs.list.stream().filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).findFirst().orElse("");
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
        return new OpenCodeHttpClient.MessageSnapshot(id, "assistant", parts);
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
