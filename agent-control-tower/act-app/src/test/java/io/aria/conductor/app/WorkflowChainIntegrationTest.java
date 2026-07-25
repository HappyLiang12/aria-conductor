package io.aria.conductor.app;

import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.common.model.WorkflowChain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REST journey over workflow chain orchestration: a two-step chain auto-starts into
 * RUNNING, cancel skips the in-flight step, non-running chains can be deleted, and two
 * FAILED chains can be merged into a fresh chain with all steps re-queued.
 * <p>
 * Adapted step: FAILED fixtures are seeded via {@link WorkflowChainRepository} instead of
 * REST. Creating a chain with a bogus agentId over REST cannot yield FAILED: the inner
 * transactional createRun marks the shared transaction rollback-only, so the request
 * surfaces as 500 (UnexpectedRollbackException) rather than a persisted FAILED chain.
 */
@Import(NoopLlmTestConfig.class)
class WorkflowChainIntegrationTest extends BaseH2IntegrationTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    WorkflowChainRepository workflowChainRepository;

    // ---- helpers ----

    private String createAgent(String name) {
        Map<String, Object> request = Map.of(
                "name", name,
                "agentType", "NATIVE",
                "description", "Workflow chain test agent"
        );
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/v1/agents", request, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) response.getBody().get("id");
    }

    private ResponseEntity<Map> createWorkflow(String name, String agentId, int stepCount) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (int i = 0; i < stepCount; i++) {
            steps.add(Map.of(
                    "agentId", agentId,
                    "promptTemplate", "Step " + i + ": do work",
                    "maxIterations", 3
            ));
        }
        return restTemplate.postForEntity(
                "/api/v1/workflows", Map.of("name", name, "steps", steps), Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> steps(Map<String, Object> workflowBody) {
        return (List<Map<String, Object>>) workflowBody.get("steps");
    }

    private Map<String, Object> stepAt(Map<String, Object> workflowBody, int index) {
        return steps(workflowBody).stream()
                .filter(s -> ((Number) s.get("index")).intValue() == index)
                .findFirst()
                .orElseThrow();
    }

    /** Seeds a persisted FAILED chain with a single FAILED step for the given agent. */
    private String createFailedWorkflow(String name, String agentId) {
        String stepsJson = "[{\"agentId\":\"" + agentId
                + "\",\"promptTemplate\":\"Step 0: do work\",\"maxIterations\":3,"
                + "\"runId\":null,\"status\":\"FAILED\",\"output\":\"FAILED: boom\"}]";
        WorkflowChain chain = workflowChainRepository.save(WorkflowChain.builder()
                .name(name)
                .status(WorkflowChain.Status.FAILED)
                .currentStepIndex(0)
                .stepsJson(stepsJson)
                .build());
        // Visible as FAILED through the REST surface too
        ResponseEntity<Map> get = restTemplate.getForEntity(
                "/api/v1/workflows/" + chain.getId(), Map.class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get.getBody().get("status")).isEqualTo("FAILED");
        assertThat(stepAt(get.getBody(), 0).get("status")).isEqualTo("FAILED");
        return chain.getId().toString();
    }

    // ==================== create -> auto-RUNNING -> cancel ====================

    @Test
    void twoStepWorkflow_autoStartsRunning_cancelSkipsCurrentStep() {
        String agentId = createAgent("ChainAgent-" + UUID.randomUUID().toString().substring(0, 8));
        ResponseEntity<Map> created = createWorkflow("Two Step Chain", agentId, 2);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Synchronous snapshot from createAndStart: first step running, second queued
        Map<String, Object> body = created.getBody();
        assertThat(body.get("status")).isEqualTo("RUNNING");
        assertThat(body.get("totalSteps")).isEqualTo(2);
        assertThat(body.get("currentStepIndex")).isEqualTo(0);
        assertThat(stepAt(body, 0).get("status")).isEqualTo("RUNNING");
        assertThat(stepAt(body, 0).get("runId")).isNotNull();
        assertThat(stepAt(body, 0).get("agentId")).isEqualTo(agentId);
        assertThat(stepAt(body, 1).get("status")).isEqualTo("PENDING");
        assertThat(stepAt(body, 1).get("runId")).isNull();

        String wfId = (String) body.get("id");
        ResponseEntity<Map> cancelled = restTemplate.postForEntity(
                "/api/v1/workflows/" + wfId + "/cancel", Map.of(), Map.class);
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody().get("status")).isEqualTo("CANCELLED");
        // The step that was in flight at cancel time is marked SKIPPED
        int cancelledIndex = ((Number) cancelled.getBody().get("currentStepIndex")).intValue();
        assertThat(stepAt(cancelled.getBody(), cancelledIndex).get("status")).isEqualTo("SKIPPED");

        // Persisted state matches the cancel response
        ResponseEntity<Map> get = restTemplate.getForEntity("/api/v1/workflows/" + wfId, Map.class);
        assertThat(get.getBody().get("status")).isEqualTo("CANCELLED");
        assertThat(stepAt(get.getBody(), cancelledIndex).get("status")).isEqualTo("SKIPPED");
    }

    // ==================== delete non-running ====================

    @Test
    void delete_nonRunningWorkflow_returns204AndRemovesIt() {
        String agentId = createAgent("DelChainAgent-" + UUID.randomUUID().toString().substring(0, 8));
        ResponseEntity<Map> created = createWorkflow("Delete Candidate", agentId, 1);
        String wfId = (String) created.getBody().get("id");

        // Move out of RUNNING first — RUNNING chains are not deletable
        restTemplate.postForEntity("/api/v1/workflows/" + wfId + "/cancel", Map.of(), Map.class);

        ResponseEntity<Void> delete = restTemplate.exchange(
                "/api/v1/workflows/" + wfId, HttpMethod.DELETE, null, Void.class);
        assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> get = restTemplate.getForEntity("/api/v1/workflows/" + wfId, Map.class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void delete_unknownWorkflow_returns404() {
        ResponseEntity<Void> delete = restTemplate.exchange(
                "/api/v1/workflows/" + UUID.randomUUID(), HttpMethod.DELETE, null, Void.class);
        assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ==================== merge two FAILED workflows ====================

    @Test
    void mergeTwoFailedWorkflows_concatenatesStepsIntoNewChain() {
        String agentId = createAgent("MergeAgent-" + UUID.randomUUID().toString().substring(0, 8));
        String failed1 = createFailedWorkflow("Failed Source A", agentId);
        String failed2 = createFailedWorkflow("Failed Source B", agentId);

        ResponseEntity<Map> merged = restTemplate.postForEntity(
                "/api/v1/workflows/merge",
                Map.of("sourceIds", List.of(failed1, failed2), "name", "Merged Recovery"),
                Map.class);
        assertThat(merged.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = merged.getBody();
        assertThat(body.get("id")).isNotIn(failed1, failed2);
        assertThat(body.get("name")).isEqualTo("Merged Recovery");
        assertThat(body.get("totalSteps")).isEqualTo(2);
        // Steps are re-queued and auto-started: first step running against the real agent
        assertThat(body.get("status")).isEqualTo("RUNNING");
        assertThat(stepAt(body, 0).get("status")).isEqualTo("RUNNING");
        assertThat(stepAt(body, 0).get("agentId")).isEqualTo(agentId);
        assertThat(stepAt(body, 1).get("status")).isEqualTo("PENDING");

        // Sources are left untouched by the merge
        for (String sourceId : List.of(failed1, failed2)) {
            ResponseEntity<Map> get = restTemplate.getForEntity(
                    "/api/v1/workflows/" + sourceId, Map.class);
            assertThat(get.getBody().get("status")).isEqualTo("FAILED");
            assertThat(get.getBody().get("totalSteps")).isEqualTo(1);
        }
    }

    // ==================== negative paths ====================

    @Test
    void merge_withSingleSource_returns400() {
        String failed = createFailedWorkflow("Lonely Source", UUID.randomUUID().toString());
        ResponseEntity<Map> merged = restTemplate.postForEntity(
                "/api/v1/workflows/merge",
                Map.of("sourceIds", List.of(failed), "name", "Not Enough Sources"),
                Map.class);
        assertThat(merged.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void cancel_failedWorkflow_returns400() {
        String failed = createFailedWorkflow("Already Failed", UUID.randomUUID().toString());
        ResponseEntity<Map> cancel = restTemplate.postForEntity(
                "/api/v1/workflows/" + failed + "/cancel", Map.of(), Map.class);
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
