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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

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
 */
@Import(NoopLlmTestConfig.class)
class ApprovalFlowIntegrationTest extends BaseH2IntegrationTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    RunRepository runRepository;

    @Autowired
    ToolCallRepository toolCallRepository;

    @Autowired
    ApprovalRepository approvalRepository;

    // ---- helpers ----

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

        // Decide APPROVE
        ResponseEntity<Map> decide = restTemplate.postForEntity(
                "/api/v1/approvals/" + approval.getId() + "/decide",
                Map.of("approved", true, "reason", "looks safe"), Map.class);
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

        ResponseEntity<Map> decide = restTemplate.postForEntity(
                "/api/v1/approvals/" + approval.getId() + "/decide",
                Map.of("approved", false, "reason", "too risky"), Map.class);
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
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/v1/approvals/" + UUID.randomUUID() + "/decide",
                Map.of("approved", true, "reason", "n/a"), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("error")).asString().contains("Approval not found");
    }

    @Test
    void decide_isTerminal_secondDecisionIsIgnored() {
        Approval approval = seedPendingApproval("http_get", "{\"url\":\"https://example.com\"}");

        restTemplate.postForEntity(
                "/api/v1/approvals/" + approval.getId() + "/decide",
                Map.of("approved", true, "reason", "first decision"), Map.class);

        // Contradicting second decision returns 200 but must not flip the stored state
        ResponseEntity<Map> second = restTemplate.postForEntity(
                "/api/v1/approvals/" + approval.getId() + "/decide",
                Map.of("approved", false, "reason", "second decision"), Map.class);
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
