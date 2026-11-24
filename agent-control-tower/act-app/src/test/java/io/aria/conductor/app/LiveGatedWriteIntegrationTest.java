package io.aria.conductor.app;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.aria.live.LiveBridge;
import io.aria.conductor.aria.live.LiveEventRouter;
import io.aria.conductor.aria.live.LiveSession;
import io.aria.conductor.aria.live.LiveSessionRegistry;
import io.aria.conductor.aria.live.LiveToolCallSink;
import io.aria.conductor.aria.live.LiveWebSocketHandler;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.approval.ApprovalGate;
import io.aria.conductor.execution.repository.ApprovalRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The gated-write lifecycle of a live turn, against the real database (spec X15): the
 * production tool-call sink receives {@code start_agent_run}, persists exactly one
 * PENDING approval on the turn's live run, and the operator's decision is what turns that
 * ask into exactly one engine run — or into none at all, plus {@code [tower] denied}.
 *
 * <p>The approval row is the only handle the REST approval surface has, so this test
 * enters through the sink a socket uses ({@link LiveToolCallSink#onToolCall}), decides
 * through the production {@link ApprovalGate}, and reads every result back from the
 * repository — never from the fixture. It also proves the two non-decision ends of the
 * lifecycle: finalize and socket close must expire the ask, because the live row carries
 * no expiry and no other sweep owns it.
 */
@Import(NoopLlmTestConfig.class)
class LiveGatedWriteIntegrationTest extends BaseH2IntegrationTest {

    private static final String CALL_ID = "call-it-1";

    @Autowired
    private LiveWebSocketHandler handler;

    @Autowired
    private LiveSessionRegistry sessions;

    @Autowired
    private LiveToolCallSink toolCalls;

    @Autowired
    private ApprovalGate approvalGate;

    @Autowired
    private ApprovalRepository approvalRepository;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private TestRestTemplate restTemplate;

    private final RecordingBridge bridge = new RecordingBridge();
    private final RecordingPhone phone = new RecordingPhone();
    private WebSocketSession ws;
    private LiveSession session;
    private UUID targetAgentId;

    /** Aria backs the turn's live run; the target is the agent the approved write starts. */
    @BeforeEach
    void setUp() {
        ensureAgent(AriaConstants.ARIA_AGENT_ID, "Aria", "assistant");
        targetAgentId = UUID.randomUUID();
        ensureAgent(targetAgentId, "ops-worker", "worker");

        ws = mock(WebSocketSession.class);
        when(ws.getId()).thenReturn("it-live-write-" + UUID.randomUUID());
        when(ws.getAttributes()).thenReturn(new ConcurrentHashMap<>());
        try {
            doAnswer(invocation -> {
                phone.frames.add(((TextMessage) invocation.getArgument(0)).getPayload());
                return null;
            }).when(ws).sendMessage(any());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        handler.afterConnectionEstablished(ws);
        session = sessions.get(ws.getId());
        assertThat(session).as("the socket is registered like a real upgrade").isNotNull();
        session.bridge(bridge);
    }

    @AfterEach
    void closeSocket() {
        if (ws != null) {
            handler.afterConnectionClosed(ws, CloseStatus.NORMAL);
            ws = null;
        }
    }

    @Test
    void anApprovedStartAgentRunPersistsOneAskThenCreatesExactlyOneRun() {
        UUID runId = callStartAgentRun();
        Approval ask = theOnlyPendingAsk(runId);

        assertThat(ask.getRunId()).isEqualTo(runId);
        assertThat(ask.getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(ask.getToolName()).isEqualTo("start_agent_run");
        assertThat(ask.getRiskTier()).isEqualTo("WRITE_LOCAL");
        assertThat(committedStatus(runId)).isEqualTo(RunStatus.WAITING_INPUT);
        assertThat(runRepository.findByAgentId(targetAgentId))
                .as("a gated write performs nothing before the decision")
                .isEmpty();

        approvalGate.decideApproval(ask.getId(), true, null);

        assertThat(approvalRepository.findById(ask.getId()))
                .get().extracting(Approval::getStatus).isEqualTo(ApprovalStatus.APPROVED);
        List<Run> created = runRepository.findByAgentId(targetAgentId);
        assertThat(created).as("one approval starts exactly one run").hasSize(1);
        assertThat(created.get(0).getPromptSeed()).isEqualTo("ship it");
        assertThat(committedStatus(runId)).isEqualTo(RunStatus.COMPLETED);
        assertThat(bridge.lines).anyMatch(line -> line.contains("\"type\":\"tool_result\"")
                && line.contains("\"id\":\"" + CALL_ID + "\"") && line.contains("\"ok\":true"));
        assertThat(bridge.lines).anyMatch(line -> line.contains("\"type\":\"tower\"")
                && line.contains("[tower] {"));
    }

    @Test
    void aDeniedStartAgentRunCreatesNoRunAndTellsTheModel() {
        UUID runId = callStartAgentRun();
        Approval ask = theOnlyPendingAsk(runId);

        approvalGate.decideApproval(ask.getId(), false, null);

        assertThat(approvalRepository.findById(ask.getId()))
                .get().extracting(Approval::getStatus).isEqualTo(ApprovalStatus.DENIED);
        assertThat(runRepository.findByAgentId(targetAgentId))
                .as("a denied approval performs nothing")
                .isEmpty();
        assertThat(committedStatus(runId)).isEqualTo(RunStatus.FAILED);
        assertThat(bridge.lines).anyMatch(line -> line.contains("\"type\":\"tower\"")
                && line.contains(LiveEventRouter.TOWER_DENIED));
    }

    @Test
    void aFinalizeExpiresTheAskItReleases() {
        UUID runId = callStartAgentRun();
        Approval ask = theOnlyPendingAsk(runId);

        ResponseEntity<String> finalize = restTemplate.postForEntity(
                "/api/v1/runs/{id}/finalize", null, String.class, runId);

        assertThat(finalize.getStatusCode().value()).isEqualTo(202);
        assertThat(approvalRepository.findById(ask.getId()))
                .get().extracting(Approval::getStatus).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(committedStatus(runId)).isEqualTo(RunStatus.ABORTED);
    }

    @Test
    void aSocketCloseExpiresTheAskItAbandons() {
        UUID runId = callStartAgentRun();
        Approval ask = theOnlyPendingAsk(runId);

        handler.afterConnectionClosed(ws, CloseStatus.NORMAL);
        ws = null;

        assertThat(approvalRepository.findById(ask.getId()))
                .get().extracting(Approval::getStatus).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(committedStatus(runId)).isEqualTo(RunStatus.ABORTED);
    }

    // --- harness ---------------------------------------------------------------------------

    /** Drives the production sink exactly as the socket's reader does, then waits for the park. */
    private UUID callStartAgentRun() {
        toolCalls.onToolCall(session, "{\"type\":\"tool_call\",\"id\":\"" + CALL_ID
                + "\",\"name\":\"start_agent_run\",\"arguments\":{\"agentId\":\"" + targetAgentId
                + "\",\"prompt\":\"ship it\"}}");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(session.runId()).as("the tool call creates the turn's live run").isNotNull();
            assertThat(approvalRepository.findByRunId(session.runId())).hasSize(1);
        });
        assertThat(phone.frames).anyMatch(frame -> frame.contains("\"type\":\"approval_required\"")
                && frame.contains("\"toolName\":\"start_agent_run\""));
        return session.runId();
    }

    private Approval theOnlyPendingAsk(UUID runId) {
        List<Approval> asks = approvalRepository.findByRunId(runId);
        assertThat(asks).as("one gated call creates one approval").hasSize(1);
        return asks.get(0);
    }

    private RunStatus committedStatus(UUID runId) {
        return runRepository.findStateById(runId).map(RunRepository.RunStateView::getStatus).orElse(null);
    }

    private void ensureAgent(UUID id, String name, String role) {
        if (agentRepository.findById(id).isPresent()) {
            return;
        }
        agentRepository.save(Agent.builder()
                .id(id).name(name).description("live gated write integration test")
                .agentType(AgentType.ADK).role(role).model("gpt-4o-mini")
                .provider("openai").config("{}").healthStatus(HealthStatus.HEALTHY)
                .createdAt(Instant.now()).build());
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

    private static final class RecordingPhone {
        private final List<String> frames = new CopyOnWriteArrayList<>();
    }
}
