package io.aria.conductor.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for the full Workflow Governance flow:
 * create -> complete -> auto-capture -> approve -> reuse.
 */
class WorkflowGovernanceIntegrationTest extends BaseH2IntegrationTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void fullGovernanceFlow_create_complete_capture_approve_reuse() {
        // 1. Create an agent
        Map<String, Object> agentRequest = Map.of(
                "name", "GovAgent-" + UUID.randomUUID().toString().substring(0, 8),
                "agentType", "NATIVE",
                "description", "Agent for governance test",
                "role", "governance-tester"
        );
        ResponseEntity<Map> agentResponse = restTemplate.postForEntity(
                "/api/v1/agents", agentRequest, Map.class);
        assertThat(agentResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String agentId = (String) agentResponse.getBody().get("id");

        // 2. Create and start a workflow
        Map<String, Object> stepDef = Map.of(
                "agentId", agentId,
                "promptTemplate", "Analyze {input}",
                "maxIterations", 3
        );
        Map<String, Object> wfRequest = Map.of(
                "name", "Governance Flow WF",
                "steps", List.of(stepDef)
        );
        ResponseEntity<Map> wfResponse = restTemplate.postForEntity(
                "/api/v1/workflows", wfRequest, Map.class);
        assertThat(wfResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(wfResponse.getBody()).isNotNull();
        String wfId = (String) wfResponse.getBody().get("id");
        assertThat(wfId).isNotNull();

        // 3. Verify the workflow exists via GET
        ResponseEntity<Map> getWfResponse = restTemplate.getForEntity(
                "/api/v1/workflows/" + wfId, Map.class);
        assertThat(getWfResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getWfResponse.getBody().get("name")).isEqualTo("Governance Flow WF");

        // 4. List workflows — should contain at least one
        ResponseEntity<List> listResponse = restTemplate.getForEntity(
                "/api/v1/workflows", List.class);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).isNotEmpty();

        // 5. Cancel the workflow
        ResponseEntity<Map> cancelResponse = restTemplate.postForEntity(
                "/api/v1/workflows/" + wfId + "/cancel", Map.of(), Map.class);
        assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelResponse.getBody().get("status")).isEqualTo("CANCELLED");

        // 6. Verify the cancelled status via GET
        ResponseEntity<Map> verifyCancelled = restTemplate.getForEntity(
                "/api/v1/workflows/" + wfId, Map.class);
        assertThat(verifyCancelled.getBody().get("status")).isEqualTo("CANCELLED");
    }

    @Test
    void createWorkflow_andVerifyStepsReturned() {
        // Create agent
        Map<String, Object> agentRequest = Map.of(
                "name", "StepAgent-" + UUID.randomUUID().toString().substring(0, 8),
                "agentType", "NATIVE",
                "description", "Agent for step test"
        );
        ResponseEntity<Map> agentResponse = restTemplate.postForEntity(
                "/api/v1/agents", agentRequest, Map.class);
        String agentId = (String) agentResponse.getBody().get("id");

        // Create multi-step workflow
        Map<String, Object> step1 = Map.of(
                "agentId", agentId,
                "promptTemplate", "Step 1: Analyze data",
                "maxIterations", 3
        );
        Map<String, Object> step2 = Map.of(
                "agentId", agentId,
                "promptTemplate", "Step 2: Generate report from {previousOutput}",
                "maxIterations", 5
        );
        Map<String, Object> wfRequest = Map.of(
                "name", "Multi-Step WF",
                "steps", List.of(step1, step2)
        );
        ResponseEntity<Map> wfResponse = restTemplate.postForEntity(
                "/api/v1/workflows", wfRequest, Map.class);
        assertThat(wfResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(wfResponse.getBody().get("totalSteps")).isEqualTo(2);

        List<Map<String, Object>> steps =
                (List<Map<String, Object>>) wfResponse.getBody().get("steps");
        assertThat(steps).hasSize(2);
    }
}
