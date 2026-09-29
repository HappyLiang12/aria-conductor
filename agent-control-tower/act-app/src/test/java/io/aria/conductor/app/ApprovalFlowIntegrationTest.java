package io.aria.conductor.app;

import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.ToolCall;
import io.aria.conductor.common.model.ToolCallStatus;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REST journey over the human-approval flow (#24): a pending approval surfaces in the
 * operator queue enriched with tool details, an APPROVE decision unblocks the tool call
 * (EXECUTING), a DENY decision rejects it (DENIED), and decisions are terminal.
 * <p>
 * Adapted step: there is no REST endpoint that creates an approval (they are created
 * internally by ApprovalGate while an agent loop is blocked), so the run / tool-call /
 * pending approval triplet is seeded through the repositories against a REST-created
 * agent, and the journey continues over the real endpoints from there.
 * <p>
 * The decision route is operator-only (spec §6.2): every decision here presents the
 * separately configured operator bearer credential — a worker credential would be 403.
 */
@Import(NoopLlmTestConfig.class)
@TestPropertySource(properties = "aria.operator.bearer-token=" + ApprovalFlowIntegrationTest.OPERATOR_CREDENTIAL)
class ApprovalFlowIntegrationTest extends BaseH2IntegrationTest {

    static final String OPERATOR_CREDENTIAL = "approval-flow-operator-credential";

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    RunRepository runRepository;

    @Autowired
    ToolCallRepository toolCallRepository;

    @Autowired
    ApprovalRepository approvalRepository;

    @Autowired
    org.springframework.context.ApplicationContext applicationContext;

    // ---- helpers ----

    /** The one authenticated operator of this installation; never a worker token. */
    private ResponseEntity<Map> decide(UUID approvalId, boolean approved, String reason) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(OPERATOR_CREDENTIAL);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(
                "/api/v1/approvals/" + approvalId + "/decide", HttpMethod.POST,
                new HttpEntity<>(Map.of("approved", approved, "reason", reason), headers), Map.class);
    }

    private String createAgent(String name) {
        Map<String, Object> request = Map.of(
                "name", name,
                "agentType", "NATIVE",
                "description", "Approval flow test agent"
        );
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/v1/agents", request, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) response.getBody().get("id");
    }

    /** Seeds run -> tool call -> PENDING approval and returns the approval. */
    private Approval seedPendingApproval(String toolName, String arguments) {
        String agentId = createAgent("ApprovalAgent-" + UUID.randomUUID().toString().substring(0, 8));
        Run run = runRepository.save(Run.builder()
                .agentId(UUID.fromString(agentId))
                .status(RunStatus.RUNNING)
                .promptSeed("approval flow test")
                .build());
        ToolCall toolCall = toolCallRepository.save(ToolCall.builder()
                .runId(run.getId())
                .toolName(toolName)
                .arguments(arguments)
                .status(ToolCallStatus.PENDING)
                .build());
        return approvalRepository.save(Approval.builder()
                .runId(run.getId())
                .toolCallId(toolCall.getId())
                .status(ApprovalStatus.PENDING)
                .reason("Agent requests approval to execute " + toolName)
                .expiresAt(Instant.now().plusSeconds(3600))
                .build());
    }

    private List<Map<String, Object>> listPending() {
        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                "/api/v1/approvals?status=PENDING", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private Map<String, Object> findById(List<Map<String, Object>> list, UUID id) {
        return list.stream()
                .filter(m -> id.toString().equals(m.get("id")))
                .findFirst()
                .orElse(null);
    }

    // ==================== APPROVE path ====================

    @Test
    void approveJourney_listsPendingDetail_thenApproveUpdatesApprovalAndToolCall() {
        Approval approval = seedPendingApproval("shell_exec", "{\"cmd\":\"ls\"}");

        // Pending queue exposes the enriched detail for an informed decision
        Map<String, Object> detail = findById(listPending(), approval.getId());
        assertThat(detail).as("seeded approval must appear in pending list").isNotNull();
        assertThat(detail.get("status")).isEqualTo("PENDING");
        assertThat(detail.get("runId")).isEqualTo(approval.getRunId().toString());
        assertThat(detail.get("toolCallId")).isEqualTo(approval.getToolCallId().toString());
        assertThat(detail.get("toolName")).isEqualTo("shell_exec");
        assertThat(detail.get("arguments")).isEqualTo("{\"cmd\":\"ls\"}");
        assertThat(detail.get("riskTier")).as("risk tier is resolved for known tool names").isNotNull();

        // Decide APPROVE (with the operator credential)
        ResponseEntity<Map> decide = decide(approval.getId(), true, "looks safe");
        assertThat(decide.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(decide.getBody().get("approvalId")).isEqualTo(approval.getId().toString());
        assertThat(decide.getBody().get("approved")).isEqualTo(true);
        assertThat(decide.getBody().get("status")).isEqualTo("processed");

        // DB state: approval APPROVED with decision metadata, tool call unblocked to EXECUTING
        Approval decided = approvalRepository.findById(approval.getId()).orElseThrow();
        assertThat(decided.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(decided.getReason()).isEqualTo("looks safe");
        assertThat(decided.getDecidedAt()).isNotNull();
        ToolCall toolCall = toolCallRepository.findById(approval.getToolCallId()).orElseThrow();
        assertThat(toolCall.getStatus()).isEqualTo(ToolCallStatus.EXECUTING);

        // Decided approvals leave the pending queue
        assertThat(findById(listPending(), approval.getId())).isNull();
    }

    // ==================== DENY path ====================

    @Test
    void denyJourney_marksApprovalDeniedAndToolCallDenied() {
        Approval approval = seedPendingApproval("write_file", "{\"path\":\"/etc/passwd\"}");

        ResponseEntity<Map> decide = decide(approval.getId(), false, "too risky");
        assertThat(decide.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(decide.getBody().get("approved")).isEqualTo(false);

        Approval decided = approvalRepository.findById(approval.getId()).orElseThrow();
        assertThat(decided.getStatus()).isEqualTo(ApprovalStatus.DENIED);
        assertThat(decided.getReason()).isEqualTo("too risky");
        assertThat(decided.getDecidedAt()).isNotNull();
        ToolCall toolCall = toolCallRepository.findById(approval.getToolCallId()).orElseThrow();
        assertThat(toolCall.getStatus()).isEqualTo(ToolCallStatus.DENIED);

        // Single-approval GET reflects the terminal decision
        ResponseEntity<Map> get = restTemplate.getForEntity(
                "/api/v1/approvals/" + approval.getId(), Map.class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get.getBody().get("status")).isEqualTo("DENIED");
    }

    // ==================== negative paths ====================

    @Test
    void decide_onUnknownApproval_returns400WithError() {
        ResponseEntity<Map> response = decide(UUID.randomUUID(), true, "n/a");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("error")).asString().contains("Approval not found");
    }

    /** Without the operator credential the decision route is closed (401). */
    @Test
    void decide_withoutOperatorCredential_returns401() {
        Approval approval = seedPendingApproval("shell_exec", "{\"cmd\":\"ls\"}");

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/v1/approvals/" + approval.getId() + "/decide",
                Map.of("approved", true, "reason", "anonymous"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("error")).isEqualTo("Operator session required");
        assertThat(approvalRepository.findById(approval.getId()).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.PENDING);
    }

    /** A valid worker credential is authenticated but never operator-authorized (403). */
    @Test
    void decide_withWorkerCredential_returns403AndLeavesTheApprovalPending() {
        Approval approval = seedPendingApproval("shell_exec", "{\"cmd\":\"ls\"}");
        io.aria.conductor.execution.security.ActorTokenService actorTokens =
                applicationContext.getBean(io.aria.conductor.execution.security.ActorTokenService.class);
        String workerToken = actorTokens.issueWorker(approval.getRunId(), Instant.now().plusSeconds(600));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(workerToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/approvals/" + approval.getId() + "/decide", HttpMethod.POST,
                new HttpEntity<>(Map.of("approved", true, "reason", "self-approval"), headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("error")).isEqualTo("Operator authority required");
        assertThat(approvalRepository.findById(approval.getId()).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void decide_isTerminal_secondDecisionIsIgnored() {
        Approval approval = seedPendingApproval("http_get", "{\"url\":\"https://example.com\"}");

        decide(approval.getId(), true, "first decision");

        // Contradicting second decision returns 200 but must not flip the stored state
        ResponseEntity<Map> second = decide(approval.getId(), false, "second decision");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);

        Approval decided = approvalRepository.findById(approval.getId()).orElseThrow();
        assertThat(decided.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(decided.getReason()).isEqualTo("first decision");
        assertThat(toolCallRepository.findById(approval.getToolCallId()).orElseThrow().getStatus())
                .isEqualTo(ToolCallStatus.EXECUTING);
    }

    @Test
    void getApproval_unknownId_returns404() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
                "/api/v1/approvals/" + UUID.randomUUID(), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
