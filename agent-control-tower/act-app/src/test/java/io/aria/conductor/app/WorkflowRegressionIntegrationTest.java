package io.aria.conductor.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression integration tests to ensure the original workflow endpoints
 * still work after the governance feature additions.
 */
class WorkflowRegressionIntegrationTest extends BaseH2IntegrationTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    // ---- helpers ----

    private String createAgent(String name) {
        Map<String, Object> request = Map.of(
                "name", name,
                "agentType", "NATIVE",
                "description", "Regression test agent"
        );
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/v1/agents", request, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) response.getBody().get("id");
    }

    // ==================== Original endpoints ====================

    @Test
    void originalCreateEndpoint_shouldStillWork() {
        String agentId = createAgent("RegressCreate-" + UUID.randomUUID().toString().substring(0, 8));

        Map<String, Object> stepDef = Map.of(
                "agentId", agentId,
                "promptTemplate", "Analyze the data",
                "maxIterations", 3
        );
        Map<String, Object> wfRequest = Map.of(
                "name", "Regression Create WF",
                "steps", List.of(stepDef)
        );

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/v1/workflows", wfRequest, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("id")).isNotNull();
        assertThat(response.getBody().get("name")).isEqualTo("Regression Create WF");
        assertThat(response.getBody().get("totalSteps")).isEqualTo(1);
    }

    @Test
    void originalListEndpoint_shouldStillWork() {
        // Create at least one workflow to ensure list is not empty
        String agentId = createAgent("RegressList-" + UUID.randomUUID().toString().substring(0, 8));
        Map<String, Object> stepDef = Map.of(
                "agentId", agentId,
                "promptTemplate", "List test step",
                "maxIterations", 3
        );
        restTemplate.postForEntity("/api/v1/workflows",
                Map.of("name", "List Regression WF", "steps", List.of(stepDef)),
                Map.class);

        ResponseEntity<List> response = restTemplate.getForEntity(
                "/api/v1/workflows", List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).isNotEmpty();
    }

    @Test
    void originalGetEndpoint_shouldStillWork() {
        String agentId = createAgent("RegressGet-" + UUID.randomUUID().toString().substring(0, 8));
        Map<String, Object> stepDef = Map.of(
                "agentId", agentId,
                "promptTemplate", "Get test step",
                "maxIterations", 3
        );
        ResponseEntity<Map> createResponse = restTemplate.postForEntity(
                "/api/v1/workflows",
                Map.of("name", "Get Regression WF", "steps", List.of(stepDef)),
                Map.class);
        String wfId = (String) createResponse.getBody().get("id");

        ResponseEntity<Map> getResponse = restTemplate.getForEntity(
                "/api/v1/workflows/" + wfId, Map.class);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody()).isNotNull();
        assertThat(getResponse.getBody().get("id")).isEqualTo(wfId);
        assertThat(getResponse.getBody().get("name")).isEqualTo("Get Regression WF");
    }

    @Test
    void originalGetEndpoint_nonExistent_shouldReturnError() {
        UUID randomId = UUID.randomUUID();
        ResponseEntity<Map> response = restTemplate.getForEntity(
                "/api/v1/workflows/" + randomId, Map.class);

        // Should return 404 or 500 depending on exception handler
        assertThat(response.getStatusCode().is4xxClientError()
                || response.getStatusCode().is5xxServerError()).isTrue();
    }

    // ==================== Flyway migration ====================

    @Test
    void flywayMigrationV18_shouldApplySuccessfully() {
        // Verify that the V18 migration columns exist on the workflow_chains table
        // is_template, template_params, source_knowledge_item_id, knowledge_item_id, description
        Integer isTemplateCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_NAME = 'WORKFLOW_CHAINS' AND COLUMN_NAME = 'IS_TEMPLATE'",
                Integer.class);
        assertThat(isTemplateCount).isEqualTo(1);

        Integer templateParamsCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_NAME = 'WORKFLOW_CHAINS' AND COLUMN_NAME = 'TEMPLATE_PARAMS'",
                Integer.class);
        assertThat(templateParamsCount).isEqualTo(1);

        Integer sourceKnowledgeItemIdCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_NAME = 'WORKFLOW_CHAINS' AND COLUMN_NAME = 'SOURCE_KNOWLEDGE_ITEM_ID'",
                Integer.class);
        assertThat(sourceKnowledgeItemIdCount).isEqualTo(1);

        Integer knowledgeItemIdCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_NAME = 'WORKFLOW_CHAINS' AND COLUMN_NAME = 'KNOWLEDGE_ITEM_ID'",
                Integer.class);
        assertThat(knowledgeItemIdCount).isEqualTo(1);

        Integer descriptionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_NAME = 'WORKFLOW_CHAINS' AND COLUMN_NAME = 'DESCRIPTION'",
                Integer.class);
        assertThat(descriptionCount).isEqualTo(1);
    }
}
