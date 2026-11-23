package io.aria.conductor.app;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.aria.live.LiveBridge;
import io.aria.conductor.aria.live.LiveEventRouter;
import io.aria.conductor.aria.live.LiveRunService;
import io.aria.conductor.aria.live.LiveSession;
import io.aria.conductor.aria.live.LiveSessionRegistry;
import io.aria.conductor.aria.live.LiveWebSocketHandler;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.common.event.ApprovalDecidedEvent;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.port.LiveRunPark;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The approval decision reaches a parked live run through
 * {@code @TransactionalEventListener(AFTER_COMMIT)}: the listener runs after the operator's
 * transaction committed, on the very thread that still holds that transaction's resources.
 * A settle that joins it writes into an already-committed transaction and is never
 * committed itself, so the phone is told {@code finished}/{@code failed} while the row
 * stays {@code WAITING_INPUT} forever.
 *
 * <p>This test publishes the event the way {@code ApprovalGate} does — inside a real
 * transaction, from the H2-backed application — and asserts, after the publish returns,
 * the row's <em>committed</em> status. It reads through the repository, never the
 * publishing persistence context.
 */
@Import(NoopLlmTestConfig.class)
class LiveApprovalCommitIntegrationTest extends BaseH2IntegrationTest {

    @Autowired
    private LiveWebSocketHandler handler;

    @Autowired
    private LiveSessionRegistry sessions;

    @Autowired
    private LiveEventRouter router;

    @Autowired
    private LiveRunService runs;

    @Autowired
    private LiveRunPark park;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Fixture fixture;

    /** {@code runs.agent_id} is a foreign key; the class cleanup truncates the Aria row. */
    @BeforeEach
    void ensureAriaAgent() {
        if (agentRepository.findById(AriaConstants.ARIA_AGENT_ID).isPresent()) {
            return;
        }
        agentRepository.save(Agent.builder()
                .id(AriaConstants.ARIA_AGENT_ID).name("Aria").description("live approval commit integration test")
                .agentType(AgentType.ADK).role("assistant").model("gpt-4o-mini")
                .provider("openai").config("{}").healthStatus(HealthStatus.HEALTHY)
                .createdAt(Instant.now()).build());
    }

    /** The close path owns the hello deadline; the socket is released between tests. */
    @AfterEach
    void closeSocket() {
        if (fixture != null) {
            handler.afterConnectionClosed(fixture.ws, CloseStatus.NORMAL);
            fixture = null;
        }
    }

    @Test
    void anApprovedDecisionCommitsCompletedBeforeThePublishReturns() {
        fixture = parkOnAGatedCall();

        decide(fixture.approvalId, ApprovalStatus.APPROVED);

        assertThat(committedStatus(fixture.runId))
                .as("the settle must be committed, not left in the decision's transaction")
                .isEqualTo(RunStatus.COMPLETED);
        assertThat(park.parked(fixture.runId)).as("the decision claimed the park").isFalse();
        assertThat(fixture.bridge.lines).anyMatch(line -> line.contains("\"type\":\"tool_result\"")
                && line.contains("\"id\":\"call-live-1\"") && line.contains("\"ok\":true"));
        assertThat(fixture.bridge.lines).anyMatch(line -> line.contains("\"type\":\"tower\"")
                && line.contains("[tower] {"));
        assertThat(fixture.phone).anyMatch(frame -> frame.contains("\"type\":\"run_event\"")
                && frame.contains("\"state\":\"finished\""));
    }

    @Test
    void aDeniedDecisionCommitsFailedBeforeThePublishReturns() {
        fixture = parkOnAGatedCall();

        decide(fixture.approvalId, ApprovalStatus.DENIED);

        assertThat(committedStatus(fixture.runId))
                .as("the settle must be committed, not left in the decision's transaction")
                .isEqualTo(RunStatus.FAILED);
        assertThat(park.parked(fixture.runId)).isFalse();
        assertThat(fixture.bridge.lines).anyMatch(line -> line.contains("\"type\":\"tool_result\"")
                && line.contains("\"id\":\"call-live-1\"") && line.contains("\"ok\":false"));
        assertThat(fixture.bridge.lines).anyMatch(line -> line.contains("\"type\":\"tower\"")
                && line.contains(LiveEventRouter.TOWER_DENIED));
        assertThat(fixture.phone).anyMatch(frame -> frame.contains("\"type\":\"run_event\"")
                && frame.contains("\"state\":\"failed\""));
    }

    /**
     * A socket parked on a gated call, entered through the production seam: the handler
     * registers the session (as a real upgrade does), the run is lazily created like a
     * first tool call, and the router parks it. {@code get_time} is the approved tool:
     * it is in the read-only live set and answers a value with no argument of its own.
     */
    private Fixture parkOnAGatedCall() {
        WebSocketSession ws = mock(WebSocketSession.class);
        String wsId = "it-live-commit-" + UUID.randomUUID();
        when(ws.getId()).thenReturn(wsId);
        when(ws.getAttributes()).thenReturn(new ConcurrentHashMap<>());
        List<String> phone = new CopyOnWriteArrayList<>();
        try {
            doAnswer(invocation -> {
                phone.add(((TextMessage) invocation.getArgument(0)).getPayload());
                return null;
            }).when(ws).sendMessage(any());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        handler.afterConnectionEstablished(ws);
        LiveSession session = sessions.get(wsId);
        assertThat(session).as("the socket is registered like a real upgrade").isNotNull();

        RecordingBridge bridge = new RecordingBridge();
        session.bridge(bridge);
        UUID runId = runs.findOrCreateForToolTurn(session, "get_time");
        UUID approvalId = UUID.randomUUID();
        router.parkForApproval(session, runId, approvalId, "get_time", "READ", "call-live-1", null);
        assertThat(committedStatus(runId)).isEqualTo(RunStatus.WAITING_INPUT);
        assertThat(park.parked(runId)).isTrue();
        return new Fixture(ws, bridge, phone, runId, approvalId);
    }

    /** {@code ApprovalGate.decideApproval} publishes inside its own transaction: so does this. */
    private void decide(UUID approvalId, ApprovalStatus decision) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status ->
                events.publishEvent(new ApprovalDecidedEvent(this, approvalId, decision)));
    }

    private RunStatus committedStatus(UUID runId) {
        return runRepository.findStateById(runId).map(RunRepository.RunStateView::getStatus).orElse(null);
    }

    private record Fixture(WebSocketSession ws, RecordingBridge bridge, List<String> phone,
                           UUID runId, UUID approvalId) {
    }

    private static final class RecordingBridge implements LiveBridge {

        private final List<String> lines = new CopyOnWriteArrayList<>();

        @Override
        public void send(String json) {
            lines.add(json);
        }

        @Override
        public void sendBinary(byte[] pcm) {
        }

        @Override
        public void close() {
        }
    }
}
