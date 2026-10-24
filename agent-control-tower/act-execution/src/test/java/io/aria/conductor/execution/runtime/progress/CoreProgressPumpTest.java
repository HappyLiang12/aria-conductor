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
import static org.assertj.core.api.Assertions.assertThatCode;
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

    /**
     * #103 growth defect (a), the real wire shape: opencode keeps ONE text part on
     * the assistant message and grows its {@code text} member in place, so the
     * part's index never moves. A watermark of part counts emits that part once,
     * with whatever it held at that poll, and every later growth is dropped -- the
     * operator watches half an answer until {@code run.completed}. A progress row is
     * an observation of a part, so a grown part is a new observation.
     */
    @Test
    void aTextPartThatGrowsBetweenPollsIsStreamedAgainWithTheGrowthInsteadOfFreezing() {
        String opening = "Distributed consensus begins with Raft,";
        String complete = "Distributed consensus begins with Raft, and Raft needs a majority"
                + " of voters to commit an entry before it is durable.";
        stubFor(get(urlEqualTo(PATH))
                .inScenario("growth")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"t1\", \"type\": \"text\", \"text\": \"" + opening + "\" }")))
                .willSetStateTo("grown"));
        stubFor(get(urlEqualTo(PATH))
                .inScenario("growth")
                .whenScenarioStateIs("grown")
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"t1\", \"type\": \"text\", \"text\": \"" + complete + "\" }"))));

        startPump();
        // Poll 3 is only issued after poll 2's batch went through the diff, so
        // seeing it proves the grown snapshot was compared against the first one.
        await().atMost(Duration.ofSeconds(3)).until(() -> requestsToThePumpPath() >= 3);
        pump.stop();

        assertThat(events)
                .as("the same part's growth is a new observation, not a dropped one")
                .hasSize(2);
        assertThat(events.get(0).content()).isEqualTo(opening);
        assertThat(events.get(1).content())
                .as("the last observation holds the full text")
                .isEqualTo(complete);
        assertThat(events).extracting(CoreProgressPump.ProgressFragment::partId)
                .as("one part grown between polls is observed twice under one identity")
                .containsExactly("t1", "t1");
        assertThat(events).extracting(CoreProgressPump.ProgressFragment::seq)
                .containsExactly(1L, 2L);
        assertThat(events).allSatisfy(e -> assertThat(e.kind()).isEqualTo(RunProgressEvent.Kind.ANSWER));
    }

    /**
     * #103 growth defect, negative half (b): a part whose content did not change is
     * re-listed on every poll and must not re-emit. Emitting on change rather than
     * on index movement only bounds row volume if this stays true.
     */
    @Test
    void anUnchangedPartIsNotReEmittedOnLaterPolls() {
        stubFor(get(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"t1\", \"type\": \"text\", \"text\": \"steady answer\" },"
                                + "{ \"id\": \"r1\", \"type\": \"reasoning\", \"text\": \"steady thought\" }"))));

        startPump();
        await().atMost(Duration.ofSeconds(3)).until(() -> requestsToThePumpPath() >= 4);
        pump.stop();

        assertThat(events)
                .as("many polls of an unchanged snapshot produce one observation per part")
                .hasSize(2);
        assertThat(events).extracting(CoreProgressPump.ProgressFragment::content)
                .containsExactly("steady answer", "steady thought");
    }

    /**
     * #103 growth defect (c): a tool part is listed while running and its
     * {@code state.output} only arrives when the tool finishes -- the part count does
     * not move, so the stale "running" row is all the operator ever sees. The
     * part's terminal status is a new observation of the same part.
     */
    @Test
    void aToolPartWhoseOutputOnlyArrivesOnCompletionIsStreamedAgainAtCompletion() {
        stubFor(get(urlEqualTo(PATH))
                .inScenario("tool")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"call1\", \"type\": \"tool\", \"tool\": \"bash\","
                                + " \"state\": { \"status\": \"running\" } }")))
                .willSetStateTo("completed"));
        stubFor(get(urlEqualTo(PATH))
                .inScenario("tool")
                .whenScenarioStateIs("completed")
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"call1\", \"type\": \"tool\", \"tool\": \"bash\","
                                + " \"state\": { \"status\": \"completed\", \"output\": \"3 tests passed\" } }"))));

        startPump();
        await().atMost(Duration.ofSeconds(3)).until(() -> requestsToThePumpPath() >= 3);
        pump.stop();

        assertThat(events)
                .as("the completed output of the same tool part is a new observation")
                .hasSize(2);
        assertThat(events.get(0).kind()).isEqualTo(RunProgressEvent.Kind.TOOL_CALL);
        assertThat(events.get(1).kind()).isEqualTo(RunProgressEvent.Kind.TOOL_RESULT);
        assertThat(events.get(1).content())
                .as("the last row holds the output, not the stale in-flight value")
                .isEqualTo("3 tests passed");
        assertThat(events).extracting(CoreProgressPump.ProgressFragment::partId)
                .as("the call and its result are two observations of one tool part")
                .containsExactly("call1", "call1");
        assertThat(events).allSatisfy(e -> assertThat(e.toolName()).isEqualTo("bash"));
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
        assertThat(cursor.trackedPartCount())
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

        // The diff ran against the tracked parts it started with (m1-p0, m1-p1) and
        // the seed applied atomically afterwards: exactly the one part newer than that
        // pair was emitted, and the cursor now holds the single-part seed — not the
        // three-part diff view nor a hybrid of the two.
        assertThat(diffed.get()).hasSize(1);
        assertThat(diffed.get().get(0).seq()).isEqualTo(1L);
        assertThat(cursor.trackedMessageCount()).isEqualTo(1);
        assertThat(cursor.trackedPartCount())
                .as("the seed replaced the tracked parts atomically, it did not merge into them")
                .isEqualTo(1);
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
        CoreProgressPump.ProgressCursor cursor = new CoreProgressPump.ProgressCursor();
        // Three messages tracked before; the server now reports only two (a real
        // shrink), so the reset path replays from the start of the new snapshot.
        cursor.seedFrom(List.of(message("m0", 0), message("m1", 0), message("m2", 0)));
        pump = new CoreProgressPump(client, "sess-1", runId, agentId, events::add,
                POLL, COALESCE, cursor);
        List<OpenCodeHttpClient.MessageSnapshot> shrunk = List.of(
                message("m1", 7), message("m2", 7));

        List<CoreProgressPump.ProgressFragment> fragments = pump.diff(shrunk);

        // 14 new parts (7 + 7) after the shrink; only the latest 10 survive.
        assertThat(fragments).hasSize(10);
        assertThat(fragments).extracting(CoreProgressPump.ProgressFragment::content)
                .containsExactly("m1-p4", "m1-p5", "m1-p6",
                        "m2-p0", "m2-p1", "m2-p2", "m2-p3", "m2-p4", "m2-p5", "m2-p6");
        assertThat(fragments).extracting(CoreProgressPump.ProgressFragment::seq)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        // The cursor reflects the whole shrunk snapshot, not just the replayed tail:
        // a later poll must not re-send the four capped-away parts.
        assertThat(cursor.trackedMessageCount()).isEqualTo(2);
        assertThat(cursor.trackedPartCount())
                .as("all 14 shrunk-snapshot parts are tracked, the 4 dropped ones included")
                .isEqualTo(14);
        assertThat(pump.diff(shrunk))
                .as("the capped replay does not re-send the dropped parts on the next poll")
                .isEmpty();
    }

    /**
     * The acceptance shape of the #103 growth fix, on the real wire shape: two
     * {@code GET /session/:id/message} responses in which the same assistant message
     * keeps ONE text part whose {@code text} grows. The observations share that
     * part's id, increase in seq, and the last one holds the full text — which is what
     * lets the drawer update one line and the persisted backlog replay the same growth.
     */
    @Test
    void theGrowthOfOneTextPartIsOnePartIdentityObservedTwiceInAscendingSeqOrder() {
        String opening = "Consensus needs";
        String full = "Consensus needs a majority of the voters to commit an entry.";
        stubFor(get(urlEqualTo(PATH))
                .inScenario("wire-growth")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"prt_1\", \"type\": \"text\", \"text\": \"" + opening + "\" }")))
                .willSetStateTo("grown"));
        stubFor(get(urlEqualTo(PATH))
                .inScenario("wire-growth")
                .whenScenarioStateIs("grown")
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"prt_1\", \"type\": \"text\", \"text\": \"" + full + "\" }"))));

        startPump();
        await().atMost(Duration.ofSeconds(3)).until(() -> requestsToThePumpPath() >= 3);
        pump.stop();

        assertThat(events).hasSize(2);
        assertThat(events).extracting(CoreProgressPump.ProgressFragment::partId)
                .as("both observations are of the same part")
                .containsExactly("prt_1", "prt_1");
        assertThat(events).extracting(CoreProgressPump.ProgressFragment::seq)
                .as("the later observation of one part has a strictly greater seq")
                .containsExactly(1L, 2L);
        assertThat(events.get(1).content())
                .as("the last observation is the full text, not the opening snapshot")
                .isEqualTo(full);
    }

    /**
     * A core that reports no part id yields no part identity: the pump keeps the
     * growth observable (positionally tracked) but never invents an id, so the
     * consumer's seq fallback stays the documented behaviour rather than a guess.
     */
    @Test
    void aPartWithoutANativeIdIsStillGrowthTrackedButIsEmittedWithNoPartId() {
        stubFor(get(urlEqualTo(PATH))
                .inScenario("unidentified")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"type\": \"text\", \"text\": \"first half\" }")))
                .willSetStateTo("grown"));
        stubFor(get(urlEqualTo(PATH))
                .inScenario("unidentified")
                .whenScenarioStateIs("grown")
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"type\": \"text\", \"text\": \"first half, then the rest\" }"))));

        startPump();
        await().atMost(Duration.ofSeconds(3)).until(() -> requestsToThePumpPath() >= 3);
        pump.stop();

        assertThat(events).hasSize(2);
        assertThat(events).extracting(CoreProgressPump.ProgressFragment::partId)
                .as("no id on the wire is no id on the fragment")
                .containsOnlyNulls();
        assertThat(events).extracting(CoreProgressPump.ProgressFragment::seq)
                .containsExactly(1L, 2L);
        assertThat(events.get(1).content()).isEqualTo("first half, then the rest");
    }

    // ---- Lane E2: the terminal drain (the PROGRESS rows=0 defect) ----

    /**
     * A poll interval longer than any test runtime: the worker makes exactly ONE poll
     * before sleeping, so the next request on the wire is the drain's own fetch and the
     * observed cadence can be scripted attempt by attempt instead of racing a 20ms loop.
     */
    private static final Duration LONGER_THAN_THE_TEST = Duration.ofSeconds(30);

    /**
     * Lane E2 RED, reproducing the OBSERVED cadence of a real opencode 1.18.15 turn
     * (probe logs aria-live-host/probe-parts.log, runs d94c7d1a / 8fef5962 / 37bc2584):
     * while the synchronous {@code POST /session/:id/message} is in flight the list
     * route serves an assistant SKELETON — the message with no parts, then
     * {@code [step-start, reasoning(text:"")]}, and {@code reasoning(len=0)} for seven
     * consecutive polls — and the content then arrives in ONE write. The assistant
     * {@code text} part becomes final only at/after the POST returns. With the 2s poll
     * interval the pump is therefore halted without ever seeing either part, which is
     * exactly the live {@code PROGRESS rows=0}. {@code drain()} is the one final fetch
     * after the prompt returns and before the pump halts.
     */
    @Test
    void theTerminalDrainRecoversTheReasoningAndTheAnswerTheLivePollsOnlySawAsBlankSkeletons() {
        String reasoning = "The user wants an essay; I will describe Raft's majority rule.";
        String answer = "Distributed consensus begins with Raft, and Raft needs a majority"
                + " of voters to commit an entry before it is durable.";
        stubFor(get(urlEqualTo(PATH))
                .inScenario("terminal")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200)
                        .withBody(messageWithRole("assistant", "")))
                .willSetStateTo("skeleton"));
        stubFor(get(urlEqualTo(PATH))
                .inScenario("terminal")
                .whenScenarioStateIs("skeleton")
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"s1\", \"type\": \"step-start\" },"
                                + "{ \"id\": \"r1\", \"type\": \"reasoning\", \"text\": \"\" }")))
                .willSetStateTo("written"));
        stubFor(get(urlEqualTo(PATH))
                .inScenario("terminal")
                .whenScenarioStateIs("written")
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"s1\", \"type\": \"step-start\" },"
                                + "{ \"id\": \"r1\", \"type\": \"reasoning\", \"text\": \"" + reasoning + "\" },"
                                + "{ \"id\": \"t1\", \"type\": \"text\", \"text\": \"" + answer + "\" }"))));

        pump = new CoreProgressPump(client, "sess-1", runId, agentId, events::add,
                LONGER_THAN_THE_TEST, COALESCE);
        pump.start();
        await().atMost(Duration.ofSeconds(3)).until(() -> requestsToThePumpPath() >= 1);

        pump.drain();
        pump.stop();

        assertThat(events)
                .as("the drain's fetch is the only one that saw either part; the live"
                        + " polls saw a skeleton and the blank reasoning, which burn no row")
                .hasSize(2);
        assertThat(events).extracting(CoreProgressPump.ProgressFragment::kind)
                .containsExactly(RunProgressEvent.Kind.THINKING, RunProgressEvent.Kind.ANSWER);
        assertThat(events).extracting(CoreProgressPump.ProgressFragment::partId)
                .as("exactly one observation of each terminal part")
                .containsExactly("r1", "t1");
        assertThat(events).extracting(CoreProgressPump.ProgressFragment::seq)
                .as("the skeleton burned no seq: the drained rows are 1 and 2")
                .containsExactly(1L, 2L);
        assertThat(events.get(0).content()).isEqualTo(reasoning);
        assertThat(events.get(1).content()).isEqualTo(answer);
        assertThat(requestsToThePumpPath())
                .as("one live poll plus the drain's two bounded attempts")
                .isEqualTo(3);
    }

    /**
     * The drain's own bound: the blank skeleton is the OBSERVED steady state of a part
     * that never gets written, so "not settled yet" must not turn the drain into a
     * second polling loop. Two attempts per call, then it returns — and a second call
     * spends two more, never a runaway.
     */
    @Test
    void theDrainIsBoundedToItsAttemptsInsteadOfBecomingASecondPollingLoop() {
        stubFor(get(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"s1\", \"type\": \"step-start\" },"
                                + "{ \"id\": \"r1\", \"type\": \"reasoning\", \"text\": \"\" }"))));

        pump = new CoreProgressPump(client, "sess-1", runId, agentId, events::add,
                LONGER_THAN_THE_TEST, COALESCE);
        pump.start();
        await().atMost(Duration.ofSeconds(3)).until(() -> requestsToThePumpPath() >= 1);

        pump.drain();
        assertThat(requestsToThePumpPath())
                .as("one poll + the two drain attempts, and no more")
                .isEqualTo(3);
        assertThat(events).as("a part that was never written produces no row").isEmpty();

        pump.drain();
        assertThat(requestsToThePumpPath())
                .as("a repeated drain is bounded the same way: it is not a loop")
                .isEqualTo(5);
    }

    /**
     * #111's acceptance rule, not a nice-to-have: a halted session stays silent. The
     * cancel path stops the pump and must produce nothing afterwards, so a drain called
     * on an already-halted pump performs NO poll at all — not "a poll that happens to
     * emit nothing".
     */
    @Test
    void aDrainAfterStopEmitsNothingAndPerformsNoPoll() {
        stubFor(get(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"r1\", \"type\": \"reasoning\", \"text\": \"outlining\" },"
                                + "{ \"id\": \"t1\", \"type\": \"text\", \"text\": \"the answer\" }"))));

        startPump();
        await().atMost(Duration.ofSeconds(3)).until(() -> !events.isEmpty());
        pump.stop();

        int requestsAtHalt = requestsToThePumpPath();
        int eventsAtHalt = events.size();

        pump.drain();

        assertThat(requestsToThePumpPath())
                .as("a halted pump does not even read the message list")
                .isEqualTo(requestsAtHalt);
        assertThat(events).hasSize(eventsAtHalt);
    }

    /**
     * The drain reuses the part-keyed cursor, so a part the live polls already streamed
     * at its final content is not streamed twice: the terminal fetch sees no change and
     * the assistant message carries nothing unfinished, which also means the drain stops
     * after ONE attempt instead of spending the bound.
     */
    @Test
    void theDrainDoesNotReEmitAPartAlreadyStreamedAtItsFinalContent() {
        stubFor(get(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"r1\", \"type\": \"reasoning\", \"text\": \"outlining the essay\" },"
                                + "{ \"id\": \"t1\", \"type\": \"text\", \"text\": \"Consensus needs a majority.\" }"))));

        pump = new CoreProgressPump(client, "sess-1", runId, agentId, events::add,
                LONGER_THAN_THE_TEST, COALESCE);
        pump.start();
        await().atMost(Duration.ofSeconds(3)).until(() -> requestsToThePumpPath() >= 1);
        int afterTheLivePoll = requestsToThePumpPath();
        assertThat(events).hasSize(2);

        pump.drain();
        pump.stop();

        assertThat(events)
                .as("the terminal fetch saw two parts it had already streamed: no third row")
                .hasSize(2);
        assertThat(events).extracting(f -> f.partId() + "=" + f.content())
                .as("no two rows name the same part with the same content")
                .doesNotHaveDuplicates();
        assertThat(requestsToThePumpPath())
                .as("settled content stops the drain after its first attempt, not after its bound")
                .isEqualTo(afterTheLivePoll + 1);
    }

    /**
     * A degraded drain attempt is retried inside the same bound instead of losing the
     * terminal content — and the retry is the drain's own business: the poll loop's
     * stall counters stay untouched, because they count consecutive LOOP polls and a
     * single failed terminal fetch is not a stalled progress stream.
     */
    @Test
    void aDegradedDrainAttemptIsRetriedWithinTheBoundAndFeedsNoStallCounter() {
        String answer = "Consensus needs a majority.";
        stubFor(get(urlEqualTo(PATH))
                .inScenario("degraded-drain")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"r1\", \"type\": \"reasoning\", \"text\": \"\" }")))
                .willSetStateTo("drain-500"));
        stubFor(get(urlEqualTo(PATH))
                .inScenario("degraded-drain")
                .whenScenarioStateIs("drain-500")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("drain-final"));
        stubFor(get(urlEqualTo(PATH))
                .inScenario("degraded-drain")
                .whenScenarioStateIs("drain-final")
                .willReturn(aResponse().withStatus(200).withBody(body(
                        "{ \"id\": \"r1\", \"type\": \"reasoning\", \"text\": \"outlining\" },"
                                + "{ \"id\": \"t1\", \"type\": \"text\", \"text\": \"" + answer + "\" }"))));

        pump = new CoreProgressPump(client, "sess-1", runId, agentId, events::add,
                LONGER_THAN_THE_TEST, COALESCE);
        pump.start();
        await().atMost(Duration.ofSeconds(3)).until(() -> requestsToThePumpPath() >= 1);

        pump.drain();
        pump.stop();

        assertThat(requestsToThePumpPath())
                .as("one poll, one degraded drain attempt, one retry that got the content")
                .isEqualTo(3);
        assertThat(events).extracting(CoreProgressPump.ProgressFragment::kind)
                .containsExactly(RunProgressEvent.Kind.THINKING, RunProgressEvent.Kind.ANSWER);
        assertThat(events.get(1).content()).isEqualTo(answer);
        assertThat(pump.degradedPollCount())
                .as("the drain's failed fetch is not a loop poll: the stall counters stay clean")
                .isZero();
        assertThat(pump.degradedStallSignals()).isZero();
    }

    /**
     * Nothing in the drain may reach the run: it is on the prompt's critical path. When
     * every fetch degrades, the drain spends its bound, logs, and returns normally.
     */
    @Test
    void aDrainThatOnlyMeetsDegradedPollsReturnsWithinItsBoundWithoutThrowing() {
        stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(500)));

        pump = new CoreProgressPump(client, "sess-1", runId, agentId, events::add,
                LONGER_THAN_THE_TEST, COALESCE);
        pump.start();
        await().atMost(Duration.ofSeconds(3)).until(() -> requestsToThePumpPath() >= 1);

        assertThatCode(() -> pump.drain()).doesNotThrowAnyException();

        assertThat(requestsToThePumpPath())
                .as("the degraded attempts are bounded, not retried forever")
                .isEqualTo(3);
        assertThat(events).isEmpty();
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
