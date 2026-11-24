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
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
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
                && frame.path("v").asInt() == 1
                && frame.path("state").asText().equals("approval")
                && frame.path("name").asText().equals("get_run"));
        assertThat(phone).anyMatch(frame -> frame.path("type").asText().equals("approval_required")
                && frame.path("v").asInt() == 1
                && frame.path("runId").asText().equals(runId.toString())
                && frame.path("approvalId").asText().equals(approvalId.toString())
                && frame.path("toolName").asText().equals("get_run")
                && frame.path("riskTier").asText().equals("READ"));
        // The chip and the run line carry the contract's fields and nothing else.
        assertThat(phone).filteredOn(frame -> "tool_status".equals(frame.path("type").asText()))
                .allSatisfy(frame -> assertThat(fieldNames(frame)).containsExactly("type", "v", "name", "state"));
        assertThat(phone).filteredOn(frame -> "run_event".equals(frame.path("type").asText()))
                .allSatisfy(frame -> assertThat(fieldNames(frame))
                        .containsExactly("type", "v", "runId", "state", "summary"));
    }

    @Test
    void anApprovedToolRunsOnceAndItsResultIsInjectedAsTower() {
        park();
        when(tools.executeApproved(eq("get_run"), eq(arguments)))
                .thenReturn(new LiveToolExecutor.ToolOutcome.Value("{\"id\":\"r1\"}"));
        when(runs.settle(eq(session), eq(runId), eq(RunStatus.COMPLETED), isNull()))
                .thenReturn(RunStatus.COMPLETED);

        router.onApprovalDecided(decided(ApprovalStatus.APPROVED));
        router.onApprovalDecided(decided(ApprovalStatus.APPROVED));

        verify(tools, times(1)).executeApproved(eq("get_run"), eq(arguments));
        verify(runs, times(1)).settle(eq(session), eq(runId), eq(RunStatus.COMPLETED), isNull());
        assertThat(bridge.lines()).anyMatch(line -> line.contains("\"type\":\"tool_result\"")
                && line.contains("\"id\":\"c1\"") && line.contains("\"ok\":true"));
        assertThat(bridge.lines()).anyMatch(line -> line.contains("\"type\":\"tower\"")
                && line.contains("[tower] {\\\"id\\\":\\\"r1\\\"}"));
        assertThat(phone()).anyMatch(frame -> frame.path("type").asText().equals("run_event")
                && frame.path("state").asText().equals("finished")
                && frame.path("summary").asText().equals("finished"));
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
                && frame.path("state").asText().equals("failed")
                && frame.path("summary").asText().equals("failed: denied by operator"));
    }

    @Test
    void aDeniedToolAnswersTheFunctionCallBeforeTheTowerInjection() {
        park();
        when(runs.settle(eq(session), eq(runId), eq(RunStatus.FAILED), eq("denied by operator")))
                .thenReturn(RunStatus.FAILED);

        router.onApprovalDecided(decided(ApprovalStatus.DENIED));

        int result = indexOfBridge("\"type\":\"tool_result\"");
        int tower = indexOfBridge("\"type\":\"tower\"");
        assertThat(result).as("the refused call is answered, not left waiting").isNotNegative();
        assertThat(tower).isGreaterThan(result);
        assertThat(bridge.lines().get(result))
                .contains("\"id\":\"c1\"").contains("\"ok\":false").contains("denied by operator");
    }

    /**
     * The decision arrives in the operator's transaction ({@code ApprovalGate} publishes
     * inside it), so the listener must run only once that transaction committed: a settle
     * or a write that failed here could otherwise roll the operator's decision back after
     * the bridge already saw a result.
     */
    @Test
    void anApprovedCallActsOnlyAfterTheDecisionTransactionCommits() {
        park();
        when(tools.executeApproved(eq("get_run"), eq(arguments)))
                .thenReturn(new LiveToolExecutor.ToolOutcome.Value("{\"id\":\"r1\"}"));
        when(runs.settle(eq(session), eq(runId), eq(RunStatus.COMPLETED), isNull()))
                .thenReturn(RunStatus.COMPLETED);

        try (AnnotationConfigApplicationContext context = routerContext()) {
            inDecisionTransaction(() -> {
                context.publishEvent(decided(ApprovalStatus.APPROVED));

                verifyNoInteractions(tools);
                assertThat(bridge.lines()).isEmpty();
                assertThat(park.parked(runId)).isTrue();
            });

            verify(tools).executeApproved(eq("get_run"), eq(arguments));
            assertThat(bridge.lines()).anyMatch(line -> line.contains("\"type\":\"tool_result\"")
                    && line.contains("\"ok\":true"));
            assertThat(park.parked(runId)).isFalse();
        }
    }

    /** A decision that never committed is no decision: nothing runs and the park stays. */
    @Test
    void aRolledBackDecisionIsNotActedOn() {
        park();

        try (AnnotationConfigApplicationContext context = routerContext()) {
            inDecisionTransaction(TransactionSynchronization.STATUS_ROLLED_BACK,
                    () -> context.publishEvent(decided(ApprovalStatus.APPROVED)));
        }

        verifyNoInteractions(tools);
        assertThat(bridge.lines()).isEmpty();
        assertThat(park.parked(runId)).isTrue();
    }

    /**
     * The interleaving the claim exists for: the finalize thread released the park and is
     * running its callback while the decision is being applied. The decision loses, and no
     * second tower may reach the model.
     */
    @Test
    void aFinalizeThatWinsTheRaceLeavesExactlyOneTower() {
        FinalizeWinsPark racing = new FinalizeWinsPark();
        LiveEventRouter racingRouter = new LiveEventRouter(runs, tools, registry, frames, racing);
        session.runId(runId);
        when(runs.settle(eq(session), eq(runId), eq(RunStatus.WAITING_INPUT), eq("approval pending")))
                .thenReturn(RunStatus.WAITING_INPUT);
        when(runs.settle(eq(session), eq(runId), eq(RunStatus.ABORTED),
                eq(LiveEventRouter.FINALIZE_REASON))).thenReturn(RunStatus.ABORTED);
        racingRouter.parkForApproval(session, runId, approvalId, "get_run", "READ", "c1", arguments);

        racingRouter.onApprovalDecided(decided(ApprovalStatus.APPROVED));

        verifyNoInteractions(tools);
        assertThat(bridge.lines()).noneMatch(line -> line.contains("\"type\":\"tool_result\""));
        assertThat(bridge.lines()).filteredOn(line -> line.contains("\"type\":\"tower\""))
                .singleElement().asString().contains(LiveEventRouter.TOWER_FINALIZED);
        verify(runs, times(1)).settle(eq(session), eq(runId), eq(RunStatus.ABORTED),
                eq(LiveEventRouter.FINALIZE_REASON));
        assertThat(racing.parked(runId)).isFalse();
    }

    /** A park that cannot be taken must still answer the function call Qwen is waiting on. */
    @Test
    void aParkThatCannotBeTakenAnswersTheBridgeAndCreatesNoPark() {
        session.runId(runId);
        when(runs.settle(session, runId, RunStatus.WAITING_INPUT, "approval pending"))
                .thenReturn(RunStatus.CANCELLED);

        router.parkForApproval(session, runId, approvalId, "get_run", "READ", "c1", arguments);

        assertThat(park.parked(runId)).isFalse();
        assertThat(bridge.lines()).singleElement().asString()
                .contains("\"type\":\"tool_result\"").contains("\"id\":\"c1\"")
                .contains("\"ok\":false").contains(LiveEventRouter.APPROVAL_UNAVAILABLE);
    }

    /**
     * The close window: the socket dies while the park is being registered. The park is the
     * only thing a close can release, so it must be registered before the pending call is
     * held — otherwise the close finds a call it cannot unpark, releases nothing, and the
     * row sits WAITING_INPUT with no owner (finalize is 409, the approval has no recipient).
     */
    @Test
    void aCloseBetweenTheParkAndThePendingCallStillAbortsTheRun() {
        when(runs.settle(any(LiveSession.class), eq(runId), eq(RunStatus.WAITING_INPUT),
                eq("approval pending"))).thenReturn(RunStatus.WAITING_INPUT);
        when(runs.settle(eq(session), eq(runId), eq(RunStatus.ABORTED),
                eq(LiveEventRouter.SESSION_CLOSED_REASON))).thenReturn(RunStatus.ABORTED);
        LiveSession closing = spy(new LiveSession("s-close", "conv-live", ws, null));
        closing.bridge(bridge);
        closing.runId(runId);
        doAnswer(invocation -> {
            // The socket closes here: after the park is in place, before this call is held.
            router.socketClosed(closing);
            return invocation.callRealMethod();
        }).when(closing).pendingCall(any());

        router.parkForApproval(closing, runId, approvalId, "get_time", "READ", "c1", arguments);

        verify(runs).settle(eq(closing), eq(runId), eq(RunStatus.ABORTED),
                eq(LiveEventRouter.SESSION_CLOSED_REASON));
        assertThat(park.parked(runId)).as("no park outlives the closed socket").isFalse();
    }

    @Test
    void anApprovalEventForAnotherRunIsIgnored() {
        router.onApprovalDecided(new ApprovalDecidedEvent(this, UUID.randomUUID(), ApprovalStatus.APPROVED));

        verifyNoInteractions(tools);
        verifyNoInteractions(runs);
        assertThat(bridge.lines()).isEmpty();
        assertThat(phone()).isEmpty();
    }

    /**
     * A parked run whose socket is gone has no decision route and no finalize route left.
     * Releasing the park is not enough: the row would sit WAITING_INPUT forever, so the
     * close path settles it ABORTED with a stable reason.
     */
    @Test
    void aSocketCloseAbortsTheParkedRun() {
        park();
        when(runs.settle(eq(session), eq(runId), eq(RunStatus.ABORTED),
                eq(LiveEventRouter.SESSION_CLOSED_REASON))).thenReturn(RunStatus.ABORTED);

        router.socketClosed(session);

        assertThat(park.parked(runId)).isFalse();
        verify(runs).settle(eq(session), eq(runId), eq(RunStatus.ABORTED),
                eq("Live session closed while waiting for an approval"));
        assertThat(phone()).anyMatch(frame -> frame.path("type").asText().equals("run_event")
                && frame.path("state").asText().equals("failed")
                && frame.path("summary").asText().equals("failed: " + LiveEventRouter.SESSION_CLOSED_REASON));
        verifyNoInteractions(tools);
    }

    /** The close path runs twice on a real socket; the second release must settle nothing. */
    @Test
    void aSecondSocketCloseSettlesNothing() {
        park();
        when(runs.settle(eq(session), eq(runId), eq(RunStatus.ABORTED),
                eq(LiveEventRouter.SESSION_CLOSED_REASON))).thenReturn(RunStatus.ABORTED);

        router.socketClosed(session);
        router.socketClosed(session);

        verify(runs, times(2)).settle(any(), any(), any(), any()); // the park's, then one abort
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
                && frame.path("state").asText().equals("failed")
                && frame.path("summary").asText().equals("failed: " + LiveEventRouter.FINALIZE_REASON));
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
                && frame.path("state").asText().equals("failed")
                && frame.path("summary").asText().equals("failed: cancelled"));
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

    private static List<String> fieldNames(JsonNode frame) {
        List<String> names = new ArrayList<>();
        frame.fieldNames().forEachRemaining(names::add);
        return names;
    }

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

    /** @return the index of the first bridge line containing {@code fragment}, or -1 */
    private int indexOfBridge(String fragment) {
        List<String> lines = bridge.lines();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(fragment)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The router as the production container sees it: only a registered bean gets its
     * {@code @TransactionalEventListener} turned into a transaction-bound listener, so the
     * commit boundary can be observed at all.
     */
    private AnnotationConfigApplicationContext routerContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(LiveRunService.class, () -> runs);
        context.registerBean(LiveToolExecutor.class, () -> tools);
        context.registerBean(LiveSessionRegistry.class, () -> registry);
        context.registerBean(LiveFrames.class, () -> frames);
        context.registerBean(LiveRunPark.class, () -> park);
        // What Boot's transaction auto-configuration supplies: without it every
        // @TransactionalEventListener becomes a plain synchronous listener.
        context.registerBean(TransactionalEventListenerFactory.class);
        context.registerBean(LiveEventRouter.class);
        context.refresh();
        return context;
    }

    /**
     * Runs {@code decision} the way {@code ApprovalGate} does — with a transaction active —
     * and then completes it, firing the synchronizations a transactional listener is bound
     * to. Spring delivers an AFTER_COMMIT listener from {@code afterCompletion}, so both
     * callbacks of a real commit are fired here, in the container's order.
     */
    private static void inDecisionTransaction(int completionStatus, Runnable decision) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        List<TransactionSynchronization> callbacks;
        try {
            decision.run();
            callbacks = List.copyOf(TransactionSynchronizationManager.getSynchronizations());
        } finally {
            TransactionSynchronizationManager.clear();
        }
        for (TransactionSynchronization synchronization : callbacks) {
            synchronization.afterCommit();
            synchronization.afterCompletion(completionStatus);
        }
    }

    private static void inDecisionTransaction(Runnable decision) {
        inDecisionTransaction(TransactionSynchronization.STATUS_COMMITTED, decision);
    }

    /**
     * The finalize thread claimed the park a moment before the approval did: the callback
     * is already running and the port has nothing left to give the decision.
     */
    private static final class FinalizeWinsPark implements LiveRunPark {

        private final Map<UUID, Runnable> parks = new ConcurrentHashMap<>();

        @Override
        public void park(UUID runId, Runnable onFinalize) {
            parks.put(runId, onFinalize);
        }

        @Override
        public boolean unpark(UUID runId) {
            Runnable finalize = parks.remove(runId);
            if (finalize == null) {
                return false;
            }
            finalize.run();
            return false;
        }

        @Override
        public boolean parked(UUID runId) {
            return parks.containsKey(runId);
        }
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
