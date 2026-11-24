package io.aria.conductor.app;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.aria.live.LiveBridge;
import io.aria.conductor.aria.live.LiveEventRouter;
import io.aria.conductor.aria.live.LiveRunService;
import io.aria.conductor.aria.live.LiveSession;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.RunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /api/v1/runs/{id}/finalize} on a live park (spec §"Tools, approvals, live
 * run"): the run store's existing route answers 202 for a run a phone socket parked,
 * settles it ABORTED, answers the call the bridge is waiting on (one {@code tool_result}
 * with the stable {@code approval unavailable}) and consumes the park, so the second
 * request is the honest 409.
 *
 * <p>The park is entered through the router rather than through a gated tool call: this
 * test exercises the finalize route itself, and the park it needs is the production
 * {@code LiveEventRouter.parkForApproval} entry the gated ops tools also use.
 * Everything on the route itself — the port, the conditional ABORTED write, the call's
 * answer — is the production path.
 */
@Import(NoopLlmTestConfig.class)
class LiveFinalizeIntegrationTest extends BaseH2IntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private LiveRunService runs;

    @Autowired
    private LiveEventRouter router;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private AgentRepository agentRepository;

    /**
     * {@code runs.agent_id} is a foreign key: the class cleanup truncates the agents the
     * application creates at boot, so a live run needs the Aria row back (create-only,
     * exactly what the boot initializer does).
     */
    @BeforeEach
    void ensureAriaAgent() {
        if (agentRepository.findById(AriaConstants.ARIA_AGENT_ID).isPresent()) {
            return;
        }
        agentRepository.save(Agent.builder()
                .id(AriaConstants.ARIA_AGENT_ID).name("Aria").description("live finalize integration test")
                .agentType(AgentType.ADK).role("assistant").model("gpt-4o-mini")
                .provider("openai").config("{}").healthStatus(HealthStatus.HEALTHY)
                .createdAt(Instant.now()).build());
    }

    @Test
    void finalizeOnAParkedLiveRunAnswers202SettlesAbortedAndAnswersTheCall() {
        RecordingBridge bridge = new RecordingBridge();
        // Socket-less on purpose: the park and the route are the subject, not the wire.
        LiveSession session = new LiveSession("it-" + UUID.randomUUID(), "conv-it-finalize", null, null);
        session.bridge(bridge);
        UUID runId = runs.findOrCreateForToolTurn(session, "get_run");
        router.parkForApproval(session, runId, UUID.randomUUID(), "get_run", "WRITE", "call-1", null);
        assertThat(committedStatus(runId)).isEqualTo(RunStatus.WAITING_INPUT);

        ResponseEntity<String> finalize = restTemplate.postForEntity(
                "/api/v1/runs/{id}/finalize", null, String.class, runId);

        assertThat(finalize.getStatusCode().value()).isEqualTo(202);
        assertThat(committedStatus(runId)).isEqualTo(RunStatus.ABORTED);
        // One message, the call's own answer: the bridge makes exactly one response.create
        // for it, and no tower follows the same call.
        assertThat(bridge.sent).filteredOn(line -> line.contains("call-1"))
                .singleElement().asString()
                .contains("\"type\":\"tool_result\"").contains("\"ok\":false")
                .contains(LiveEventRouter.APPROVAL_UNAVAILABLE);
        assertThat(bridge.sent).noneMatch(line -> line.contains("\"type\":\"tower\""));

        ResponseEntity<String> again = restTemplate.postForEntity(
                "/api/v1/runs/{id}/finalize", null, String.class, runId);
        assertThat(again.getStatusCode().value()).as("the park is consumed by the first finalize").isEqualTo(409);
    }

    @Test
    void finalizeOnALiveRunThatIsNotParkedStays409() {
        LiveSession session = new LiveSession("it-" + UUID.randomUUID(), "conv-it-unparked", null, null);
        UUID runId = runs.findOrCreateForToolTurn(session, "get_time");

        ResponseEntity<String> finalize = restTemplate.postForEntity(
                "/api/v1/runs/{id}/finalize", null, String.class, runId);

        assertThat(finalize.getStatusCode().value()).isEqualTo(409);
        assertThat(committedStatus(runId)).isEqualTo(RunStatus.RUNNING);
    }

    /**
     * A parked run whose socket is gone has no route left that could settle it: the
     * close path must settle it ABORTED itself, or the row sits WAITING_INPUT forever
     * with every finalize answering 409.
     */
    @Test
    void aClosedSocketAbortsTheParkedLiveRun() {
        LiveSession session = new LiveSession("it-" + UUID.randomUUID(), "conv-it-closed", null, null);
        UUID runId = runs.findOrCreateForToolTurn(session, "get_run");
        router.parkForApproval(session, runId, UUID.randomUUID(), "get_run", "WRITE", "call-2", null);
        assertThat(committedStatus(runId)).isEqualTo(RunStatus.WAITING_INPUT);

        router.socketClosed(session);

        assertThat(committedStatus(runId)).isEqualTo(RunStatus.ABORTED);
        ResponseEntity<String> finalize = restTemplate.postForEntity(
                "/api/v1/runs/{id}/finalize", null, String.class, runId);
        assertThat(finalize.getStatusCode().value()).as("the park is released, not left half-open")
                .isEqualTo(409);
    }

    private RunStatus committedStatus(UUID runId) {
        return runRepository.findStateById(runId).map(RunRepository.RunStateView::getStatus).orElse(null);
    }

    private static final class RecordingBridge implements LiveBridge {

        private final List<String> sent = new CopyOnWriteArrayList<>();

        @Override
        public void send(String json) {
            sent.add(json);
        }

        @Override
        public void sendBinary(byte[] pcm) {
        }

        @Override
        public void close() {
        }
    }
}
