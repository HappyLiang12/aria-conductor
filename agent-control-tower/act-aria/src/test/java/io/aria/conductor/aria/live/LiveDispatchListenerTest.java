package io.aria.conductor.aria.live;

import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.event.RunIterationEvent;
import io.aria.conductor.common.event.RunProgressEvent;
import io.aria.conductor.common.event.RunStartedEvent;
import io.aria.conductor.common.model.RunStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Child-run lifecycle frames (Phase 4, spec §4.2): only a registered run is observed, only
 * its socket receives frames, {@code origin:"dispatch"} marks them, and progress is a
 * heartbeat rather than a transcript.
 */
class LiveDispatchListenerTest {

    private final LiveDispatchService dispatches = new LiveDispatchService();
    private final LiveFrames frames = mock(LiveFrames.class);
    private final LiveProperties properties = new LiveProperties(false, null, null, "", 0, 0,
            false, null, null, 0, 20_000, 600_000, 20);
    private final MutableClock clock = new MutableClock();
    private final LiveDispatchListener listener =
            new LiveDispatchListener(dispatches, frames, properties, clock);
    private final LiveSession owner = new LiveSession("s1", "conv", null, null);
    private final UUID agent = UUID.randomUUID();

    @Test
    void startedAndFinishedReachOnlyTheOwningSocketWithOrigin() {
        UUID run = UUID.randomUUID();
        dispatches.register(run, owner, "Analyst");

        listener.onStarted(new RunStartedEvent(this, run, agent));
        listener.onCompleted(new RunCompletedEvent(this, run, agent, RunStatus.COMPLETED, "done"));

        ArgumentCaptor<Map<String, Object>> sent = capture();
        verify(frames, times(2)).toPhone(eq(owner), sent.capture());
        Map<String, Object> started = sent.getAllValues().get(0);
        assertThat(started.get("type")).isEqualTo("run_event");
        assertThat(started.get("runId")).isEqualTo(run.toString());
        assertThat(started.get("state")).isEqualTo("started");
        assertThat(started.get("origin")).isEqualTo("dispatch");
        assertThat(started.get("summary")).isEqualTo("running");

        Map<String, Object> finished = sent.getAllValues().get(1);
        assertThat(finished.get("state")).isEqualTo("finished");
        assertThat(finished.get("origin")).isEqualTo("dispatch");
        assertThat(dispatches.find(run)).as("terminal removes the routing entry").isEmpty();

        // The outcome is spoken by the model: one TOWER, idle because the answer in flight
        // must finish first; the completion preview is bounded.
        ArgumentCaptor<Map<String, Object>> tower = capture();
        verify(frames).toBridge(eq(owner), tower.capture());
        assertThat(tower.getValue().get("type")).isEqualTo("tower");
        assertThat(tower.getValue().get("mode")).isEqualTo("idle");
        assertThat((String) tower.getValue().get("text"))
                .startsWith("[tower] Analyst 已完成：").endsWith("done");
    }

    @Test
    void aFailedRunCarriesItsStableCodeNotTheOperatorMessage() {
        UUID run = UUID.randomUUID();
        dispatches.register(run, owner, "Analyst");

        listener.onCompleted(new RunCompletedEvent(this, run, agent, RunStatus.FAILED,
                null, "the provider said something private", "PROVIDER_UNAVAILABLE", "core"));

        ArgumentCaptor<Map<String, Object>> sent = capture();
        verify(frames).toPhone(eq(owner), sent.capture());
        assertThat(sent.getValue().get("state")).isEqualTo("failed");
        assertThat(sent.getValue().get("summary")).isEqualTo("failed: PROVIDER_UNAVAILABLE");

        ArgumentCaptor<Map<String, Object>> tower = capture();
        verify(frames).toBridge(eq(owner), tower.capture());
        assertThat(tower.getValue().get("mode")).isEqualTo("interrupt");
        String text = (String) tower.getValue().get("text");
        assertThat(text).isEqualTo("[tower] Analyst 失敗：PROVIDER_UNAVAILABLE");
        assertThat(text).doesNotContain("private");
    }

    @Test
    void progressIsCoalescedToOneFramePerSecondPerRun() {
        UUID run = UUID.randomUUID();
        dispatches.register(run, owner, "Analyst");

        listener.onIteration(new RunIterationEvent(this, run, agent, 2, 10));
        clock.advance(10);
        listener.onProgress(new RunProgressEvent(this, run, agent, 2,
                RunProgressEvent.Kind.TOOL_CALL, "secret output", "get_run", 1, null));
        verify(frames, times(1)).toPhone(eq(owner), any());

        clock.advance(LiveDispatchListener.PROGRESS_WINDOW_MS);
        listener.onIteration(new RunIterationEvent(this, run, agent, 3, 10));
        ArgumentCaptor<Map<String, Object>> sent = capture();
        verify(frames, times(2)).toPhone(eq(owner), sent.capture());
        assertThat(sent.getAllValues().get(1).get("summary")).isEqualTo("iteration 3");
    }

    @Test
    void theCompletionPreviewIsCollapsedAndBounded() {
        UUID run = UUID.randomUUID();
        dispatches.register(run, owner, "Analyst");

        listener.onCompleted(new RunCompletedEvent(this, run, agent, RunStatus.COMPLETED,
                "line one\n\nline two with a much longer tail than the preview allows"));

        ArgumentCaptor<Map<String, Object>> tower = capture();
        verify(frames).toBridge(eq(owner), tower.capture());
        String text = (String) tower.getValue().get("text");
        String preview = text.substring(text.indexOf("：") + 1);
        assertThat(preview).hasSize(20).doesNotContain("\n");
        assertThat(text).startsWith("[tower] Analyst 已完成：line one line two wi");
    }

    @Test
    void aFailureWithoutACodeSaysFailedOnceNotTwice() {
        UUID run = UUID.randomUUID();
        dispatches.register(run, owner, "Analyst");

        listener.onCompleted(new RunCompletedEvent(this, run, agent, RunStatus.FAILED));

        ArgumentCaptor<Map<String, Object>> sent = capture();
        verify(frames).toPhone(eq(owner), sent.capture());
        assertThat(sent.getValue().get("summary")).isEqualTo("failed");
        ArgumentCaptor<Map<String, Object>> tower = capture();
        verify(frames).toBridge(eq(owner), tower.capture());
        assertThat(tower.getValue().get("text")).isEqualTo("[tower] Analyst 失敗");
    }

    @Test
    void unknownRunsAndTheTurnRunAreIgnored() {
        UUID stranger = UUID.randomUUID();

        listener.onStarted(new RunStartedEvent(this, stranger, agent));
        listener.onCompleted(new RunCompletedEvent(this, stranger, agent, RunStatus.COMPLETED, "done"));

        verify(frames, never()).toPhone(any(), any());
    }

    private static ArgumentCaptor<Map<String, Object>> capture() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor =
                ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
        return captor;
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-25T00:00:00Z");

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
            return now;
        }

        void advance(long millis) {
            now = now.plusMillis(millis);
        }
    }
}
