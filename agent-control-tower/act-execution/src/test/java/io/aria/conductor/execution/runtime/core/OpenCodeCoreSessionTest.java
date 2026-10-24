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
 * {@code opencode.progress} envelope, is stopped before the terminal result is
 * visible, survives failures, keeps the session-level seq/watermark across
 * prompts and never starts after {@link OpenCodeCoreSession#close()}.
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
            if (call <= 3) {
                // turn 1's pump poll and turn 2's pre-prompt baseline
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
        // Two pre-prompt baselines and one poll per turn; no extra replay poll.
        assertThat(listCalls.get()).isEqualTo(4);
    }

    /**
     * The previous turn's pump may stop before it ever observes the assistant
     * message (a very fast turn, or a first poll that raced the server's
     * message list). The pre-prompt baseline seed must still stop the second
     * prompt from replaying that turn and restarting seq at 1.
     */
    @Test
    void aSecondPromptSeededFromTheBaselineDoesNotReplayATurnTheFirstPumpNeverSaw() throws Exception {
        AtomicInteger listCalls = new AtomicInteger();
        CountDownLatch firstPumpPolled = new CountDownLatch(1);
        when(client.listMessages(SESSION)).thenAnswer(invocation -> {
            int call = listCalls.incrementAndGet();
            if (call == 1 || call == 2) {
                // turn 1: empty pre-prompt baseline and an empty pump poll
                if (call == 2) {
                    firstPumpPolled.countDown();
                }
                return new OpenCodeHttpClient.MessageListResult(List.of(), false);
            }
            if (call == 3) {
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
        assertThat(listCalls.get()).isEqualTo(4);
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
        return new OpenCodeHttpClient.MessageSnapshot(id, List.of(parts));
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
