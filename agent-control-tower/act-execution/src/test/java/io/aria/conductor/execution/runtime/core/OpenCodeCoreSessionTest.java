package io.aria.conductor.execution.runtime.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.runtime.AgentExecutionSettings;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.execution.adk.TaskExecutionException;
import io.aria.conductor.execution.adk.opencode.OpenCodeHttpClient;
import io.aria.conductor.execution.runtime.CoreEvent;
import io.aria.conductor.execution.runtime.CoreResult;
import io.aria.conductor.execution.runtime.CoreTask;
import io.aria.conductor.execution.runtime.ExecutionSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The session's progress wiring, end to end against a controlled client: the
 * pump starts before the prompt, forwards each fragment as the exact
 * {@code opencode.progress} envelope, is drained on the success path and stopped
 * before the terminal result is visible, survives failures, keeps the
 * session-level seq and the tracked parts across prompts and never starts after
 * {@link OpenCodeCoreSession#close()}.
 */
class OpenCodeCoreSessionTest {

    private static final String SESSION = "sess-1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID RUN = UUID.randomUUID();
    private static final UUID AGENT = UUID.randomUUID();
    /**
     * The fake clock's origin: wall-clock now, so a session built on it has a
     * deadline that is genuinely in the future for both the real clock and the
     * test clock.
     */
    private static final Instant T0 = Instant.now();

    private final List<CoreEvent> events = new CopyOnWriteArrayList<>();
    private final CountDownLatch firstProgress = new CountDownLatch(1);
    private final CountDownLatch secondProgress = new CountDownLatch(1);

    private OpenCodeHttpClient client;
    private OpenCodeCoreSession session;

    @BeforeEach
    void setUp() {
        client = mock(OpenCodeHttpClient.class);
        session = new OpenCodeCoreSession(spec(Instant.now().plusSeconds(60)), client, SESSION, "efficient");
    }

    @AfterEach
    void tearDown() {
        session.close();
    }

    /**
     * Lane E2, the live defect one layer above the pump: {@code POST /session/:id/message}
     * is synchronous, and opencode 1.18.15 finalizes the assistant's text part only
     * at/after that POST returns (probe: the POST returned at t+12.2s and the very next
     * list showed text(len=1906,final)). The session stops the pump in the same finally
     * the POST returns into, so without a terminal drain the turn's rows are
     * {@code PROGRESS rows=0} — what the live QA scenario recorded at HEAD 1151df3d.
     * The drain must therefore run after the send returns and before {@code stop()}, and
     * its rows are ordered ahead of the answer envelope like any other progress.
     */
    @Test
    void aSuccessfulPromptDrainsTheTerminalPartsTheLivePollsCouldNotSee() throws Exception {
        AtomicInteger listCalls = new AtomicInteger();
        CountDownLatch skeletonPolled = new CountDownLatch(1);
        when(client.listMessages(SESSION)).thenAnswer(invocation -> {
            int call = listCalls.incrementAndGet();
            if (call == 1) {
                return new OpenCodeHttpClient.MessageListResult(List.of(), false); // pre-prompt baseline
            }
            if (call == 2) {
                // The observed skeleton: the turn marker and a reasoning part whose text
                // is still empty. The pump tracks both and streams neither.
                skeletonPolled.countDown();
                return new OpenCodeHttpClient.MessageListResult(List.of(message("a1",
                        part("s1", "step-start", null, null, null, null),
                        part("r1", "reasoning", "", null, null, null))), false);
            }
            // The drain's fetch, after the POST returned: the one write landed.
            return new OpenCodeHttpClient.MessageListResult(List.of(message("a1",
                    part("s1", "step-start", null, null, null, null),
                    part("r1", "reasoning", "outlining the essay", null, null, null),
                    part("t1", "text", "Consensus needs a majority.", null, null, null))), false);
        });
        when(client.sendPrompt(eq(SESSION), any(), anyList(), any())).thenAnswer(invocation -> {
            assertThat(skeletonPolled.await(5, TimeUnit.SECONDS)).isTrue();
            return new OpenCodeHttpClient.MessageResult(
                    "{\"info\":{\"id\":\"a1\"},\"parts\":[]}", "a1", "Consensus needs a majority.",
                    1L, 1L, "efficient", null);
        });

        session.prompt(task("prompt"), this::record).toCompletableFuture().get(10, TimeUnit.SECONDS);

        List<CoreEvent> progress = events.stream()
                .filter(event -> "opencode.progress".equals(event.type()))
                .toList();
        assertThat(progress)
                .as("without the terminal drain this is the live zero rows")
                .hasSize(2);
        JsonNode thinking = JSON.readTree(progress.get(0).payloadJson());
        assertThat(thinking.path("kind").asText()).isEqualTo("THINKING");
        assertThat(thinking.path("content").asText()).isEqualTo("outlining the essay");
        assertThat(thinking.path("partId").asText()).isEqualTo("r1");
        assertThat(thinking.path("seq").asLong()).isEqualTo(1L);
        JsonNode answer = JSON.readTree(progress.get(1).payloadJson());
        assertThat(answer.path("kind").asText()).isEqualTo("ANSWER");
        assertThat(answer.path("content").asText()).isEqualTo("Consensus needs a majority.");
        assertThat(answer.path("partId").asText()).isEqualTo("t1");
        assertThat(answer.path("seq").asLong()).isEqualTo(2L);
        assertThat(events).extracting(CoreEvent::type)
                .containsExactly("opencode.progress", "opencode.progress", "opencode.message");
        assertThat(listCalls.get())
                .as("baseline, one live poll, one settled drain attempt")
                .isEqualTo(3);
    }

    /**
     * The asymmetry is the whole point: only a turn that ANSWERED has terminal content
     * to recover. A failed or cancelled turn must not drain — that would re-open the
     * #111 rule that a halted run produces nothing after the halt, and it would spend a
     * fetch on a message list that has no finished answer in it.
     */
    @Test
    void aFailedPromptDoesNotDrain() throws Exception {
        AtomicInteger listCalls = new AtomicInteger();
        CountDownLatch skeletonPolled = new CountDownLatch(1);
        when(client.listMessages(SESSION)).thenAnswer(invocation -> {
            if (listCalls.incrementAndGet() == 1) {
                return new OpenCodeHttpClient.MessageListResult(List.of(), false); // pre-prompt baseline
            }
            skeletonPolled.countDown();
            return new OpenCodeHttpClient.MessageListResult(List.of(message("a1",
                    part("r1", "reasoning", "", null, null, null))), false);
        });
        when(client.sendPrompt(eq(SESSION), any(), anyList(), any())).thenAnswer(invocation -> {
            assertThat(skeletonPolled.await(5, TimeUnit.SECONDS)).isTrue();
            throw new TaskExecutionException(
                    TaskExecutionException.Cause.PROVIDER_ERROR, "the core refused the prompt");
        });

        CompletionStage<CoreResult> stage = session.prompt(task("prompt"), this::record);

        assertThatThrownBy(() -> stage.toCompletableFuture().get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
        assertThat(events).isEmpty();
        assertThat(listCalls.get())
                .as("baseline plus the one live poll: the failure path spends no drain fetch")
                .isEqualTo(2);
    }

    @Test
    void oneStreamedPartYieldsOneExactProgressEventBeforeTheMessageEvent() throws Exception {
        AtomicInteger listCalls = new AtomicInteger();
        when(client.listMessages(SESSION)).thenAnswer(invocation -> {
            if (listCalls.incrementAndGet() == 1) {
                return new OpenCodeHttpClient.MessageListResult(List.of(), false); // pre-prompt baseline
            }
            return new OpenCodeHttpClient.MessageListResult(
                    List.of(message("m1", part("p1", "reasoning", "locating scheduler", null, null, null))),
                    false);
        });
        String raw = "{\"info\":{\"id\":\"m9\",\"modelID\":\"efficient\"},"
                + "\"parts\":[{\"type\":\"text\",\"text\":\"done\"}]}";
        when(client.sendPrompt(eq(SESSION), any(), anyList(), any())).thenAnswer(invocation -> {
            assertThat(firstProgress.await(5, TimeUnit.SECONDS)).isTrue();
            return new OpenCodeHttpClient.MessageResult(raw, "m9", "done", 12L, 7L, "efficient", null);
        });

        CoreResult result = session.prompt(task("prompt"), this::record)
                .toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertThat(result.sessionId()).isEqualTo(SESSION);
        assertThat(result.finalOutput()).isEqualTo("done");
        assertThat(result.usage().inputTokens()).isEqualTo(12L);
        assertThat(result.usage().outputTokens()).isEqualTo(7L);
        assertThat(result.usage().observedModel()).isEqualTo("efficient");
        assertThat(result.cancelled()).isFalse();

        assertThat(events).hasSize(2);
        CoreEvent progress = events.get(0);
        assertThat(progress.type()).isEqualTo("opencode.progress");
        assertThat(progress.runId()).isEqualTo(RUN);
        assertThat(progress.sessionId()).isEqualTo(SESSION);
        assertThat(progress.requestId()).isNull();
        JsonNode payload = JSON.readTree(progress.payloadJson());
        assertThat(payload.path("kind").asText()).isEqualTo("THINKING");
        assertThat(payload.path("content").asText()).isEqualTo("locating scheduler");
        assertThat(payload.path("toolName").isNull()).isTrue();
        assertThat(payload.path("seq").asLong()).isEqualTo(1L);
        assertThat(payload.path("iteration").asInt()).isZero();

        CoreEvent message = events.get(1);
        assertThat(message.type()).isEqualTo("opencode.message");
        assertThat(message.runId()).isEqualTo(RUN);
        assertThat(message.sessionId()).isEqualTo(SESSION);
        assertThat(message.payloadJson()).isEqualTo(raw);
    }

    /**
     * #103 judged the way the live HOST QA judges it, one layer above the pump: the
     * native message list of a run-owned session holds the operator's user message
     * and the assistant's turn (marker parts included), and the
     * {@code opencode.progress} envelopes the run then persists must carry the
     * assistant's reasoning and answer content only — never the prompt echo of
     * seq 1, never the {@code step-start} STATUS of seq 2, and never an empty
     * fragment. The payload shape the coordinator consumes stays
     * {@code (kind, content, toolName, seq, iteration)}.
     */
    @Test
    void progressEventsCarryTheAssistantContentAndNeverTheOperatorPrompt() throws Exception {
        String operatorPrompt = "Write a detailed 900-word essay about distributed consensus algorithms.";
        AtomicInteger listCalls = new AtomicInteger();
        when(client.listMessages(SESSION)).thenAnswer(invocation -> {
            if (listCalls.incrementAndGet() == 1) {
                return new OpenCodeHttpClient.MessageListResult(List.of(), false); // pre-prompt baseline
            }
            return new OpenCodeHttpClient.MessageListResult(List.of(
                    message("u1", "user", part("up", "text", operatorPrompt, null, null, null)),
                    message("a1", "assistant",
                            part("as", "step-start", null, null, null, null),
                            part("ar", "reasoning", "outlining the essay", null, null, null),
                            part("ae", "reasoning", "", null, null, null),
                            part("at", "text", "Consensus needs a majority.", null, null, null))),
                    false);
        });
        when(client.sendPrompt(eq(SESSION), any(), anyList(), any())).thenAnswer(invocation -> {
            // Both fragments of the batch are recorded before the turn answers, so
            // the bounded pump halt can neither drop nor reorder them.
            assertThat(secondProgress.await(5, TimeUnit.SECONDS)).isTrue();
            return new OpenCodeHttpClient.MessageResult(
                    "{\"info\":{\"id\":\"a1\"},\"parts\":[]}", "a1", "Consensus needs a majority.",
                    1L, 1L, "efficient", null);
        });

        session.prompt(task(operatorPrompt), this::record).toCompletableFuture().get(10, TimeUnit.SECONDS);

        List<CoreEvent> progress = events.stream()
                .filter(event -> "opencode.progress".equals(event.type()))
                .toList();
        assertThat(progress).hasSize(2);
        JsonNode thinking = JSON.readTree(progress.get(0).payloadJson());
        assertThat(thinking.path("kind").asText()).isEqualTo("THINKING");
        assertThat(thinking.path("content").asText()).isEqualTo("outlining the essay");
        assertThat(thinking.path("seq").asLong()).isEqualTo(1L);
        JsonNode answer = JSON.readTree(progress.get(1).payloadJson());
        assertThat(answer.path("kind").asText()).isEqualTo("ANSWER");
        assertThat(answer.path("content").asText()).isEqualTo("Consensus needs a majority.");
        assertThat(answer.path("toolName").isNull()).isTrue();
        assertThat(answer.path("seq").asLong()).isEqualTo(2L);
        assertThat(answer.path("iteration").asInt()).isZero();
        assertThat(progress).extracting(CoreEvent::payloadJson)
                .as("no progress row echoes the operator's own prompt")
                .allSatisfy(payload -> assertThat(payload).doesNotContain(operatorPrompt));
    }

    @Test
    void sendPromptFailureCompletesTheFutureExceptionallyAndStopsPolling() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        AtomicInteger listCalls = new AtomicInteger();
        CountDownLatch polled = new CountDownLatch(1);
        when(client.listMessages(SESSION)).thenAnswer(invocation -> {
            if (listCalls.incrementAndGet() == 1) {
                return new OpenCodeHttpClient.MessageListResult(List.of(), false); // pre-prompt baseline
            }
            polls.incrementAndGet();
            polled.countDown();
            return new OpenCodeHttpClient.MessageListResult(List.of(), false);
        });
        TaskExecutionException failure = new TaskExecutionException(
                TaskExecutionException.Cause.PROVIDER_ERROR, "the core refused the prompt");
        when(client.sendPrompt(eq(SESSION), any(), anyList(), any())).thenAnswer(invocation -> {
            assertThat(polled.await(5, TimeUnit.SECONDS)).isTrue();
            throw failure;
        });

        CompletionStage<CoreResult> stage = session.prompt(task("prompt"), this::record);

        assertThatThrownBy(() -> stage.toCompletableFuture().get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .satisfies(error -> assertThat(error.getCause()).isSameAs(failure));
        assertThat(events).isEmpty();

        int pollsAtCompletion = polls.get();
        await().during(Duration.ofMillis(2300)).atMost(Duration.ofSeconds(4))
                .untilAsserted(() -> assertThat(polls.get()).isEqualTo(pollsAtCompletion));
    }

    @Test
    void noMessageListRequestIsMadeAfterThePromptCompletes() throws Exception {
        AtomicInteger listCalls = new AtomicInteger();
        when(client.listMessages(SESSION)).thenAnswer(invocation -> {
            if (listCalls.incrementAndGet() == 1) {
                return new OpenCodeHttpClient.MessageListResult(List.of(), false); // pre-prompt baseline
            }
            return new OpenCodeHttpClient.MessageListResult(
                    List.of(message("m1", part("p1", "reasoning", "first", null, null, null))), false);
        });
        when(client.sendPrompt(eq(SESSION), any(), anyList(), any())).thenAnswer(invocation -> {
            assertThat(firstProgress.await(5, TimeUnit.SECONDS)).isTrue();
            return new OpenCodeHttpClient.MessageResult(
                    "{\"info\":{\"id\":\"m1\"},\"parts\":[]}", "m1", "done", 1L, 1L, "efficient", null);
        });

        session.prompt(task("prompt"), this::record).toCompletableFuture().get(10, TimeUnit.SECONDS);

        int pollsAtCompletion = listCalls.get();
        await().during(Duration.ofMillis(2300)).atMost(Duration.ofSeconds(4))
                .untilAsserted(() -> assertThat(listCalls.get()).isEqualTo(pollsAtCompletion));
        assertThat(events).extracting(CoreEvent::type)
                .containsExactly("opencode.progress", "opencode.message");
    }

    @Test
    void closeHaltsAnInFlightPumpAndNoProgressArrivesAfterItReturns() throws Exception {
        AtomicInteger listCalls = new AtomicInteger();
        CountDownLatch releasePrompt = new CountDownLatch(1);
        when(client.listMessages(SESSION)).thenAnswer(invocation -> {
            if (listCalls.incrementAndGet() == 1) {
                return new OpenCodeHttpClient.MessageListResult(List.of(), false); // pre-prompt baseline
            }
            return new OpenCodeHttpClient.MessageListResult(
                    List.of(message("m1", part("p1", "reasoning", "first", null, null, null))), false);
        });
        when(client.sendPrompt(eq(SESSION), any(), anyList(), any())).thenAnswer(invocation -> {
            releasePrompt.await(5, TimeUnit.SECONDS);
            throw new TaskExecutionException(TaskExecutionException.Cause.ABORTED, "client closed");
        });
        doAnswer(invocation -> {
            releasePrompt.countDown();
            return null;
        }).when(client).close();

        CompletionStage<CoreResult> stage = session.prompt(task("prompt"), this::record);
        await().atMost(Duration.ofSeconds(3)).until(() -> progressCount() == 1);

        session.close();
        int progressAtClose = progressCount();
        int pollsAtClose = listCalls.get();

        assertThatThrownBy(() -> stage.toCompletableFuture().get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .satisfies(error -> assertThat(error.getCause()).isInstanceOf(TaskExecutionException.class));
        await().during(Duration.ofMillis(2300)).atMost(Duration.ofSeconds(4))
                .untilAsserted(() -> {
                    assertThat(progressCount()).isEqualTo(progressAtClose);
                    assertThat(listCalls.get()).isEqualTo(pollsAtClose);
                });
        assertThat(progressAtClose).isEqualTo(1);
        assertThat(events).noneMatch(event -> "opencode.message".equals(event.type()));
    }

    @Test
    void aSecondPromptContinuesTheSessionSeqWithoutReplayingTheFirstTurn() throws Exception {
        AtomicInteger listCalls = new AtomicInteger();
        AtomicInteger promptCalls = new AtomicInteger();
        when(client.listMessages(SESSION)).thenAnswer(invocation -> {
            int call = listCalls.incrementAndGet();
            if (call == 1) {
                return new OpenCodeHttpClient.MessageListResult(List.of(), false); // pre-prompt baseline (turn 1)
            }
            if (call <= 4) {
                // turn 1's pump poll, turn 1's terminal drain, turn 2's pre-prompt
                // baseline: the list still holds only the first turn
                return new OpenCodeHttpClient.MessageListResult(
                        List.of(message("m1", part("p1", "reasoning", "first", null, null, null))), false);
            }
            // turn 2's pump poll: the first turn is still in the native list
            return new OpenCodeHttpClient.MessageListResult(List.of(
                    message("m1", part("p1", "reasoning", "first", null, null, null)),
                    message("m2", part("p2", "reasoning", "second", null, null, null))), false);
        });
        when(client.sendPrompt(eq(SESSION), any(), anyList(), any())).thenAnswer(invocation -> {
            if (promptCalls.incrementAndGet() == 1) {
                assertThat(firstProgress.await(5, TimeUnit.SECONDS)).isTrue();
                return new OpenCodeHttpClient.MessageResult(
                        "{\"info\":{\"id\":\"m1\"},\"parts\":[]}", "m1", "one", 1L, 1L, "efficient", null);
            }
            assertThat(secondProgress.await(5, TimeUnit.SECONDS)).isTrue();
            return new OpenCodeHttpClient.MessageResult(
                    "{\"info\":{\"id\":\"m2\"},\"parts\":[]}", "m2", "two", 2L, 2L, "efficient", null);
        });

        session.prompt(task("first"), this::record).toCompletableFuture().get(10, TimeUnit.SECONDS);
        session.prompt(task("second"), this::record).toCompletableFuture().get(10, TimeUnit.SECONDS);

        List<CoreEvent> progress = events.stream()
                .filter(event -> "opencode.progress".equals(event.type()))
                .toList();
        assertThat(progress).hasSize(2);
        JsonNode first = JSON.readTree(progress.get(0).payloadJson());
        assertThat(first.path("kind").asText()).isEqualTo("THINKING");
        assertThat(first.path("content").asText()).isEqualTo("first");
        assertThat(first.path("seq").asLong()).isEqualTo(1L);
        JsonNode second = JSON.readTree(progress.get(1).payloadJson());
        assertThat(second.path("kind").asText()).isEqualTo("THINKING");
        assertThat(second.path("content").asText()).isEqualTo("second");
        assertThat(second.path("seq").asLong()).isEqualTo(2L);
        assertThat(events).extracting(CoreEvent::type)
                .containsExactly("opencode.progress", "opencode.message",
                        "opencode.progress", "opencode.message");
        // Two pre-prompt baselines, one poll and one settled drain attempt per turn:
        // the drain is bounded to a single fetch when the terminal content is already
        // what the pump streamed, so a turn costs three reads, not four.
        assertThat(listCalls.get()).isEqualTo(6);
    }

    /**
     * The previous turn's pump may stop before it ever observes the assistant
     * message (a very fast turn, or a first poll that raced the server's
     * message list) — and with the terminal drain, the whole bounded fetch of that
     * drain can race it too: the list route is the only source, so a core that has not
     * published the finished turn is simply not readable yet. The pre-prompt baseline
     * seed of the NEXT prompt is what then still stops a replay, and seq still does not
     * restart at 1.
     */
    @Test
    void aSecondPromptSeededFromTheBaselineDoesNotReplayATurnTheFirstPumpNeverSaw() throws Exception {
        AtomicInteger listCalls = new AtomicInteger();
        CountDownLatch firstPumpPolled = new CountDownLatch(1);
        when(client.listMessages(SESSION)).thenAnswer(invocation -> {
            int call = listCalls.incrementAndGet();
            if (call <= 4) {
                // turn 1: empty pre-prompt baseline, empty pump poll and both bounded
                // drain attempts — the finished turn is not listed yet
                if (call == 2) {
                    firstPumpPolled.countDown();
                }
                return new OpenCodeHttpClient.MessageListResult(List.of(), false);
            }
            if (call == 5) {
                // turn 2's pre-prompt baseline: turn 1 is now complete
                return new OpenCodeHttpClient.MessageListResult(
                        List.of(message("m1", part("p1", "reasoning", "first", null, null, null))), false);
            }
            return new OpenCodeHttpClient.MessageListResult(List.of(
                    message("m1", part("p1", "reasoning", "first", null, null, null)),
                    message("m2", part("p2", "reasoning", "second", null, null, null))), false);
        });
        when(client.sendPrompt(eq(SESSION), any(), anyList(), any())).thenAnswer(invocation -> {
            assertThat(firstPumpPolled.await(5, TimeUnit.SECONDS)).isTrue();
            return new OpenCodeHttpClient.MessageResult(
                    "{\"info\":{\"id\":\"m1\"},\"parts\":[]}", "m1", "one", 1L, 1L, "efficient", null);
        });

        session.prompt(task("first"), this::record).toCompletableFuture().get(10, TimeUnit.SECONDS);
        // Turn 1 emitted no progress; the first event overall belongs to turn 2.
        when(client.sendPrompt(eq(SESSION), any(), anyList(), any())).thenAnswer(invocation -> {
            assertThat(firstProgress.await(5, TimeUnit.SECONDS)).isTrue();
            return new OpenCodeHttpClient.MessageResult(
                    "{\"info\":{\"id\":\"m2\"},\"parts\":[]}", "m2", "two", 2L, 2L, "efficient", null);
        });
        session.prompt(task("second"), this::record).toCompletableFuture().get(10, TimeUnit.SECONDS);

        List<CoreEvent> progress = events.stream()
                .filter(event -> "opencode.progress".equals(event.type()))
                .toList();
        assertThat(progress).hasSize(1);
        JsonNode payload = JSON.readTree(progress.get(0).payloadJson());
        assertThat(payload.path("content").asText()).isEqualTo("second");
        assertThat(payload.path("seq").asLong()).isEqualTo(1L);
        // Turn 1 spent its baseline, one poll and both bounded drain attempts on an
        // empty list; turn 2 spends a baseline, a poll and one settled drain attempt.
        assertThat(listCalls.get()).isEqualTo(7);
    }

    @Test
    void promptAfterCloseIsRefusedWithoutStartingAPumpOrSendingARequest() {
        session.close();

        assertThatThrownBy(() -> session.prompt(task("prompt"), this::record)
                .toCompletableFuture().get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .satisfies(error -> assertThat(error.getCause())
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("closed"));

        verify(client, never()).listMessages(any());
        verify(client, never()).sendPrompt(any(), any(), anyList(), any());
        assertThat(events).isEmpty();
    }

    /**
     * The close-versus-prompt interleaving M-3 names: close() runs after the
     * prompt thread started but before its pump was assigned. The prompt must
     * observe the closed flag and refuse, so no pump can start after close()
     * returns and no request follows the in-flight baseline.
     */
    @Test
    void closeDuringThePrePromptBaselineWinsBeforeAnyPumpStarts() throws Exception {
        AtomicInteger listCalls = new AtomicInteger();
        CountDownLatch baselineStarted = new CountDownLatch(1);
        CountDownLatch releaseBaseline = new CountDownLatch(1);
        when(client.listMessages(SESSION)).thenAnswer(invocation -> {
            listCalls.incrementAndGet();
            baselineStarted.countDown();
            assertThat(releaseBaseline.await(5, TimeUnit.SECONDS)).isTrue();
            return new OpenCodeHttpClient.MessageListResult(List.of(), false);
        });

        CompletionStage<CoreResult> stage = session.prompt(task("prompt"), this::record);
        assertThat(baselineStarted.await(5, TimeUnit.SECONDS)).isTrue();
        session.close();
        releaseBaseline.countDown();

        assertThatThrownBy(() -> stage.toCompletableFuture().get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .satisfies(error -> assertThat(error.getCause())
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("closed"));
        verify(client, never()).sendPrompt(any(), any(), anyList(), any());
        assertThat(events).isEmpty();
        await().during(Duration.ofMillis(2300)).atMost(Duration.ofSeconds(4))
                .untilAsserted(() -> assertThat(listCalls.get()).isEqualTo(1));
    }

    /**
     * M7: the pre-prompt progress baseline is a real HTTP read that can burn the
     * whole 10s poll timeout, and it happens before the prompt is sent. The turn
     * budget must be what is LEFT of the deadline when the prompt goes out, not
     * what was left before the baseline started.
     */
    @Test
    void thePrePromptProgressBaselineIsDeductedFromThePromptBudget() throws Exception {
        MutableClock clock = new MutableClock(T0);
        OpenCodeCoreSession timed = sessionWith(T0.plusSeconds(60), clock);
        AtomicInteger listCalls = new AtomicInteger();
        when(client.listMessages(SESSION)).thenAnswer(invocation -> {
            if (listCalls.incrementAndGet() == 1) {
                // The baseline read ran to its poll timeout.
                clock.advance(Duration.ofSeconds(9));
            }
            return new OpenCodeHttpClient.MessageListResult(List.of(), false);
        });
        when(client.sendPrompt(eq(SESSION), any(), anyList(), any())).thenReturn(
                new OpenCodeHttpClient.MessageResult(
                        "{\"info\":{\"id\":\"m1\"},\"parts\":[]}", "m1", "done", 1L, 1L, "efficient", null));

        timed.prompt(task("prompt"), this::record).toCompletableFuture().get(10, TimeUnit.SECONDS);

        ArgumentCaptor<Duration> budget = ArgumentCaptor.forClass(Duration.class);
        verify(client).sendPrompt(eq(SESSION), any(), anyList(), budget.capture());
        assertThat(budget.getValue())
                .as("the 60s deadline minus the 9s the baseline consumed")
                .isEqualTo(Duration.ofSeconds(51));
    }

    /**
     * M7's edge: when the baseline consumes the whole remaining deadline, the
     * turn has no budget left and the prompt is refused with TIMEOUT instead of
     * being sent with a zero (or negative) timeout.
     */
    @Test
    void aBaselineThatAteTheWholeDeadlineRefusesThePromptInsteadOfSendingIt() {
        MutableClock clock = new MutableClock(T0);
        OpenCodeCoreSession timed = sessionWith(T0.plusSeconds(4), clock);
        AtomicInteger listCalls = new AtomicInteger();
        when(client.listMessages(SESSION)).thenAnswer(invocation -> {
            if (listCalls.incrementAndGet() == 1) {
                clock.advance(Duration.ofSeconds(5));
            }
            return new OpenCodeHttpClient.MessageListResult(List.of(), false);
        });

        assertThatThrownBy(() -> timed.prompt(task("prompt"), this::record)
                .toCompletableFuture().get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .satisfies(error -> {
                    assertThat(error.getCause()).isInstanceOf(TaskExecutionException.class);
                    assertThat(((TaskExecutionException) error.getCause()).cause())
                            .isEqualTo(TaskExecutionException.Cause.TIMEOUT);
                });

        verify(client, never()).sendPrompt(any(), any(), anyList(), any());
        assertThat(events).isEmpty();
    }

    /** A session on the test clock; the previous one is closed so no pump outlives the swap. */
    private OpenCodeCoreSession sessionWith(Instant deadline, Clock clock) {
        session.close();
        session = new OpenCodeCoreSession(spec(deadline), client, SESSION, "efficient", clock);
        return session;
    }

    /** A test clock the assertions advance themselves: budget arithmetic stays exact. */
    private static final class MutableClock extends Clock {
        private volatile Instant instant;

        MutableClock(Instant start) {
            this.instant = start;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private void record(CoreEvent event) {
        events.add(event);
        if ("opencode.progress".equals(event.type())) {
            int progressCount = progressCount();
            if (progressCount >= 1) {
                firstProgress.countDown();
            }
            if (progressCount >= 2) {
                secondProgress.countDown();
            }
        }
    }

    private int progressCount() {
        return (int) events.stream().filter(event -> "opencode.progress".equals(event.type())).count();
    }

    private static CoreTask task(String prompt) {
        return new CoreTask(null, List.of(), prompt);
    }

    private static OpenCodeHttpClient.MessageSnapshot message(String id,
            OpenCodeHttpClient.PartSnapshot... parts) {
        // The recorded wire shape carries info.role; only assistant activity streams.
        return message(id, "assistant", parts);
    }

    private static OpenCodeHttpClient.MessageSnapshot message(String id, String role,
            OpenCodeHttpClient.PartSnapshot... parts) {
        return new OpenCodeHttpClient.MessageSnapshot(id, role, List.of(parts));
    }

    private static OpenCodeHttpClient.PartSnapshot part(String id, String type, String text,
            String toolName, String state, String output) {
        return new OpenCodeHttpClient.PartSnapshot(id, type, text, toolName, state, output);
    }

    private static ExecutionSpec spec(Instant deadline) {
        return new ExecutionSpec(RUN, AGENT, "opencode", ExecutionMode.HOST,
                new AgentExecutionSettings("opencode", ExecutionMode.HOST, null, null, null),
                null, null, deadline);
    }
}
