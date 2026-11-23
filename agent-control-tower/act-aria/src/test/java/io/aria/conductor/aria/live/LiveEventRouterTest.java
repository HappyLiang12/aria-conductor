package io.aria.conductor.aria.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.event.ApprovalDecidedEvent;
import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.port.LiveRunPark;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The approval/finalize/turn-end life of a parked live run (spec §"Tools, approvals,
 * live run").
 *
 * <p>A parked live run is a row plus a callback, not a blocked engine thread: the park
 * is registered through {@link LiveRunPark} so {@code /runs/{id}/finalize} answers 202
 * and settles ABORTED, the operator's decision arrives as an {@code ApprovalDecidedEvent}
 * and is answered exactly once, and the socket's turn end settles the run only when no
 * park still stands. Everything the router writes goes either to the phone (run_event,
 * tool_status, approval_required) or down the bridge (tool_result, tower).
 */
class LiveEventRouterTest {

    private final ObjectMapper json = new ObjectMapper();
    private final LiveFrames frames = new LiveFrames(json);
    private final FakePark park = new FakePark();
    private final RecordingBridge bridge = new RecordingBridge();
    private final LiveRunService runs = mock(LiveRunService.class);
    private final LiveToolExecutor tools = mock(LiveToolExecutor.class);
    private final LiveSessionRegistry registry = new LiveSessionRegistry();
    private final LiveEventRouter router = new LiveEventRouter(runs, tools, registry, frames, park);

    private final WebSocketSession ws = mock(WebSocketSession.class);
    private LiveSession session;
    private final UUID runId = UUID.randomUUID();
    private final UUID approvalId = UUID.randomUUID();
    private final JsonNode arguments = json.readTree("{\"id\":\"6f1a95c2-0000-4000-8000-000000000000\"}");

    LiveEventRouterTest() throws Exception {
    }

    @BeforeEach
    void setUp() {
        when(ws.getId()).thenReturn("s1");
        when(ws.getAcceptedProtocol()).thenReturn(LiveWebSocketHandler.PROTOCOL);
        session = new LiveSession("s1", "conv-live", ws, null);
        session.bridge(bridge);
        registry.register(session);
    }

    @Test
    void parkingOnApprovalWaitsTheRunAndTellsThePhone() {
        session.runId(runId);
        when(runs.settle(session, runId, RunStatus.WAITING_INPUT, "approval pending"))
                .thenReturn(RunStatus.WAITING_INPUT);

        router.parkForApproval(session, runId, approvalId, "get_run", "READ", "c1", arguments);

        verify(runs).settle(eq(session), eq(runId), eq(RunStatus.WAITING_INPUT), eq("approval pending"));
        assertThat(park.parked(runId)).isTrue();
        List<JsonNode> phone = phone();
        assertThat(phone).anyMatch(frame -> frame.path("type").asText().equals("tool_status")
                && frame.path("status").asText().equals("approval")
                && frame.path("toolName").asText().equals("get_run"));
        assertThat(phone).anyMatch(frame -> frame.path("type").asText().equals("approval_required")
                && frame.path("runId").asText().equals(runId.toString())
                && frame.path("approvalId").asText().equals(approvalId.toString())
                && frame.path("toolName").asText().equals("get_run")
                && frame.path("riskTier").asText().equals("READ"));
    }

    @Test
    void anApprovedToolRunsOnceAndItsResultIsInjectedAsTower() {
        park();
        when(tools.execute(eq("get_run"), eq(arguments)))
                .thenReturn(new LiveToolExecutor.ToolOutcome.Value("{\"id\":\"r1\"}"));
        when(runs.settle(eq(session), eq(runId), eq(RunStatus.COMPLETED), isNull()))
                .thenReturn(RunStatus.COMPLETED);

        router.onApprovalDecided(decided(ApprovalStatus.APPROVED));
        router.onApprovalDecided(decided(ApprovalStatus.APPROVED));

        verify(tools, times(1)).execute(eq("get_run"), eq(arguments));
        verify(runs, times(1)).settle(eq(session), eq(runId), eq(RunStatus.COMPLETED), isNull());
        assertThat(bridge.lines()).anyMatch(line -> line.contains("\"type\":\"tool_result\"")
                && line.contains("\"id\":\"c1\"") && line.contains("\"ok\":true"));
        assertThat(bridge.lines()).anyMatch(line -> line.contains("\"type\":\"tower\"")
                && line.contains("[tower] {\\\"id\\\":\\\"r1\\\"}"));
        assertThat(phone()).anyMatch(frame -> frame.path("type").asText().equals("run_event")
                && frame.path("state").asText().equals("finished"));
        assertThat(park.parked(runId)).isFalse();
    }

    @Test
    void aDeniedToolFailsTheRunWithTheStableReason() {
        park();
        when(runs.settle(eq(session), eq(runId), eq(RunStatus.FAILED), eq("denied by operator")))
                .thenReturn(RunStatus.FAILED);

        router.onApprovalDecided(decided(ApprovalStatus.DENIED));

        verifyNoInteractions(tools);
        verify(runs).settle(eq(session), eq(runId), eq(RunStatus.FAILED), eq("denied by operator"));
        assertThat(bridge.lines()).anyMatch(line -> line.contains("\"type\":\"tower\"")
                && line.contains("[tower] denied by operator"));
        assertThat(phone()).anyMatch(frame -> frame.path("type").asText().equals("run_event")
                && frame.path("state").asText().equals("failed"));
    }

    @Test
    void anApprovalEventForAnotherRunIsIgnored() {
        router.onApprovalDecided(new ApprovalDecidedEvent(this, UUID.randomUUID(), ApprovalStatus.APPROVED));

        verifyNoInteractions(tools);
        verifyNoInteractions(runs);
        assertThat(bridge.lines()).isEmpty();
        assertThat(phone()).isEmpty();
    }

    @Test
    void aSocketCloseUnparksTheParkedRun() {
        park();

        router.socketClosed(session);

        assertThat(park.parked(runId)).isFalse();
        // The park's own WAITING_INPUT write is the only settle: closing settles nothing.
        verify(runs, times(1)).settle(any(), any(), any(), any());
        verifyNoInteractions(tools);
    }

    @Test
    void finalizingAParkedLiveRunAbortsItAndInjectsTower() {
        park();
        when(runs.settle(eq(session), eq(runId), eq(RunStatus.ABORTED),
                eq(LiveEventRouter.FINALIZE_REASON))).thenReturn(RunStatus.ABORTED);

        assertThat(park.finalize(runId)).isTrue();

        verify(runs).settle(eq(session), eq(runId), eq(RunStatus.ABORTED),
                eq("Operator finalized the live run"));
        assertThat(bridge.lines()).anyMatch(line -> line.contains("\"type\":\"tower\"")
                && line.contains("[tower] finalized"));
        assertThat(phone()).anyMatch(frame -> frame.path("type").asText().equals("run_event")
                && frame.path("state").asText().equals("failed"));
        assertThat(park.parked(runId)).isFalse();
        assertThat(park.finalize(runId)).isFalse();
    }

    @Test
    void theTurnEndSettlesTheRunOnlyWhenNothingIsParked() {
        session.runId(runId);
        when(runs.settle(eq(session), eq(runId), eq(RunStatus.COMPLETED), isNull()))
                .thenReturn(RunStatus.COMPLETED);

        router.turnDone(session);

        verify(runs).settle(eq(session), eq(runId), eq(RunStatus.COMPLETED), isNull());
        assertThat(phone()).anyMatch(frame -> frame.path("type").asText().equals("run_event")
                && frame.path("state").asText().equals("finished"));
    }

    @Test
    void theTurnEndLeavesAParkedRunWaiting() {
        park();

        router.turnDone(session);

        // Only the park's own WAITING_INPUT write: the parked run is not settled by a turn end.
        verify(runs, times(1)).settle(any(), any(), any(), any());
        assertThat(park.parked(runId)).isTrue();
    }

    @Test
    void theTurnEndWithoutAToolCallSettlesNothing() {
        router.turnDone(session);

        verifyNoInteractions(runs);
        assertThat(phone()).isEmpty();
    }

    @Test
    void aRunCompletedEventForALiveRunIsForwardedAndUnparked() {
        park();

        router.onRunCompleted(new RunCompletedEvent(this, runId, UUID.randomUUID(), RunStatus.CANCELLED));

        assertThat(park.parked(runId)).isFalse();
        assertThat(phone()).anyMatch(frame -> frame.path("type").asText().equals("run_event")
                && frame.path("state").asText().equals("failed"));
        // Only the park's own WAITING_INPUT write: the run was settled elsewhere.
        verify(runs, times(1)).settle(any(), any(), any(), any());
    }

    @Test
    void aRunCompletedEventForAnotherRunIsIgnored() {
        router.onRunCompleted(new RunCompletedEvent(this, UUID.randomUUID(), UUID.randomUUID(),
                RunStatus.COMPLETED));

        assertThat(phone()).isEmpty();
    }

    // --- harness ---------------------------------------------------------------------------

    /** A parked run for the current turn: settled WAITING_INPUT and registered for finalize. */
    private void park() {
        session.runId(runId);
        when(runs.settle(eq(session), eq(runId), eq(RunStatus.WAITING_INPUT), eq("approval pending")))
                .thenReturn(RunStatus.WAITING_INPUT);
        router.parkForApproval(session, runId, approvalId, "get_run", "READ", "c1", arguments);
    }

    private ApprovalDecidedEvent decided(ApprovalStatus status) {
        return new ApprovalDecidedEvent(this, approvalId, status);
    }

    /** Every text frame the phone received so far (none is a legal answer). */
    private List<JsonNode> phone() {
        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        try {
            verify(ws, atLeast(0)).sendMessage(sent.capture());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        List<JsonNode> frames = new ArrayList<>();
        for (TextMessage message : sent.getAllValues()) {
            try {
                frames.add(json.readTree(message.getPayload()));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return frames;
    }

    /** The in-process park seam: the callback runs when the test finalizes the run. */
    private static final class FakePark implements LiveRunPark {

        private final Map<UUID, Runnable> parks = new ConcurrentHashMap<>();

        @Override
        public void park(UUID runId, Runnable onFinalize) {
            parks.put(runId, onFinalize);
        }

        @Override
        public boolean unpark(UUID runId) {
            return parks.remove(runId) != null;
        }

        @Override
        public boolean parked(UUID runId) {
            return parks.containsKey(runId);
        }

        boolean finalize(UUID runId) {
            Runnable onFinalize = parks.remove(runId);
            if (onFinalize == null) {
                return false;
            }
            onFinalize.run();
            return true;
        }
    }

    private static final class RecordingBridge implements LiveBridge {

        private final List<String> sent = new CopyOnWriteArrayList<>();
        private final AtomicInteger closes = new AtomicInteger();

        List<String> lines() {
            return sent;
        }

        @Override
        public void send(String json) {
            sent.add(json);
        }

        @Override
        public void sendBinary(byte[] pcm) {
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }
}
