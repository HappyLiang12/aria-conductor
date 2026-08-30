package io.aria.conductor;

import io.aria.conductor.app.BaseH2IntegrationTest;
import io.aria.conductor.aria.dto.NotificationDto;
import io.aria.conductor.aria.service.NotificationService;
import io.aria.conductor.aria.service.ScheduledJobService;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.port.SchedulerPort;
import io.aria.conductor.execution.adk.AdkProvider;
import io.aria.conductor.execution.adk.AdkProviderRegistry;
import io.aria.conductor.execution.llm.LlmResponse;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class ActIntegrationTest extends BaseH2IntegrationTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    AgentRepository agentRepository;

    @MockBean
    AdkProviderRegistry adkProviderRegistry;

    private AdkProvider mockAdkProvider;

    @org.junit.jupiter.api.BeforeEach
    void setupAdkProvider() {
        mockAdkProvider = org.mockito.Mockito.mock(AdkProvider.class);
        when(adkProviderRegistry.resolve(any())).thenReturn(mockAdkProvider);
        when(mockAdkProvider.isHealthy(any())).thenReturn(true);
        // S12: the engine invokes the 4-arg call (with stream sink); Mockito mocks do
        // NOT fall through interface default methods, so stub the 4-arg form too.
        when(mockAdkProvider.call(any(), any(), any()))
                .thenReturn(new LlmResponse("I can help you manage agents.", 10, 20, "stop", List.of()));
        when(mockAdkProvider.call(any(), any(), any(), any()))
                .thenReturn(new LlmResponse("I can help you manage agents.", 10, 20, "stop", List.of()));
        when(mockAdkProvider.parseActionsFromResponse(any())).thenReturn(List.of());

        // Ensure Aria agent exists (cleanup-all.sql runs after ApplicationRunner and wipes it)
        UUID ariaId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        if (agentRepository.findById(ariaId).isEmpty()) {
            Agent aria = Agent.builder()
                    .id(ariaId)
                    .name("Aria")
                    .role("AI operator assistant")
                    .agentType(AgentType.NATIVE)
                    .adkProvider("langchain")
                    .config("{\"maxToolCallRounds\":15}")
                    .healthStatus(HealthStatus.HEALTHY)
                    .build();
            agentRepository.save(aria);
        }
    }

    @Autowired
    NotificationService notificationService;

    @Autowired
    ScheduledJobService scheduledJobService;

    @MockBean
    SchedulerPort schedulerPort;

    @Test
    void healthCheck() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/actuator/health", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status")).isEqualTo("UP");
    }

    @Test
    void createAndListAgents() {
        // Create agent
        Map<String, Object> createRequest = Map.of(
                "name", "TestAgent-" + UUID.randomUUID().toString().substring(0, 8),
                "agentType", "ADK",
                "description", "Integration test agent"
        );
        ResponseEntity<Map> createResponse = restTemplate.postForEntity(
                "/api/v1/agents", createRequest, Map.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.getBody()).isNotNull();
        assertThat(createResponse.getBody().get("id")).isNotNull();
        assertThat(createResponse.getBody().get("name")).isNotNull();

        // List agents
        ResponseEntity<List> listResponse = restTemplate.getForEntity(
                "/api/v1/agents", List.class);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).isNotNull();
        assertThat(listResponse.getBody()).isNotEmpty();
    }

    @Test
    void createRunForAgent() {
        // Create agent first
        Map<String, Object> agentRequest = Map.of(
                "name", "RunTestAgent-" + UUID.randomUUID().toString().substring(0, 8),
                "agentType", "NATIVE",
                "description", "Agent for run test"
        );
        ResponseEntity<Map> agentResponse = restTemplate.postForEntity(
                "/api/v1/agents", agentRequest, Map.class);
        String agentId = (String) agentResponse.getBody().get("id");

        // Create run
        Map<String, Object> runRequest = Map.of(
                "agentId", agentId,
                "promptSeed", "Test prompt for integration test"
        );
        ResponseEntity<Map> runResponse = restTemplate.postForEntity(
                "/api/v1/runs", runRequest, Map.class);
        assertThat(runResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(runResponse.getBody()).isNotNull();
        assertThat(runResponse.getBody().get("id")).isNotNull();
        assertThat(runResponse.getBody().get("status")).isEqualTo("PENDING");

        // Get run
        String runId = (String) runResponse.getBody().get("id");
        ResponseEntity<Map> getRunResponse = restTemplate.getForEntity(
                "/api/v1/runs/" + runId, Map.class);
        assertThat(getRunResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getRunResponse.getBody().get("status")).isIn("PENDING", "INITIALIZING", "RUNNING");
    }

    @Test
    void submitAndReviewKnowledge() {
        // Submit knowledge
        Map<String, Object> submitRequest = Map.of(
                "name", "test-prompt-" + UUID.randomUUID().toString().substring(0, 8),
                "type", "PROMPT",
                "description", "Test knowledge item",
                "content", "This is a test prompt template"
        );
        ResponseEntity<Map> submitResponse = restTemplate.postForEntity(
                "/api/v1/knowledge", submitRequest, Map.class);
        assertThat(submitResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(submitResponse.getBody()).isNotNull();
        assertThat(submitResponse.getBody().get("id")).isNotNull();

        String knowledgeId = (String) submitResponse.getBody().get("id");

        // Get knowledge — should be PENDING
        ResponseEntity<Map> getResponse = restTemplate.getForEntity(
                "/api/v1/knowledge/" + knowledgeId, Map.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody().get("status")).isEqualTo("PENDING");

        // Approve via review
        Map<String, Object> reviewRequest = Map.of(
                "decision", "APPROVED",
                "reason", "Looks good"
        );
        ResponseEntity<Map> reviewResponse = restTemplate.postForEntity(
                "/api/v1/knowledge/" + knowledgeId + "/review", reviewRequest, Map.class);
        assertThat(reviewResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reviewResponse.getBody().get("status")).isEqualTo("APPROVED");

        // Verify approved status
        ResponseEntity<Map> verifiedResponse = restTemplate.getForEntity(
                "/api/v1/knowledge/" + knowledgeId, Map.class);
        assertThat(verifiedResponse.getBody().get("status")).isEqualTo("APPROVED");
    }

    @Test
    void ariaChatResponds() {
        Map<String, Object> chatRequest = Map.of(
                "message", "Hello Aria, what can you do?"
        );
        ResponseEntity<Map> chatResponse = restTemplate.postForEntity(
                "/api/v1/aria/chat", chatRequest, Map.class);
        assertThat(chatResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(chatResponse.getBody()).isNotNull();
        assertThat(chatResponse.getBody().get("message")).isNotNull();
        assertThat(chatResponse.getBody().get("runId")).isNotNull();
    }

    @Test
    void dashboardSummary() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
                "/api/v1/dashboard/summary", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).containsKey("activeAgents");
        assertThat(response.getBody()).containsKey("runningRuns");
        assertThat(response.getBody()).containsKey("pendingApprovals");
        assertThat(response.getBody()).containsKey("totalTokensBurned");
    }

    @Test
    void approvalWorkflow() {
        // List approvals — should return 200 even if empty
        ResponseEntity<List> response = restTemplate.getForEntity(
                "/api/v1/approvals", List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void notificationEndpoints() {
        // Seed a notification via service (no POST endpoint for notifications)
        // Body exceeds VARCHAR(255) to prove TEXT column usage after @Lob removal
        String largeBody = "A".repeat(260)
                + "\nMulti-line content with unicode: 🎉 émoji 中文测试\n"
                + "Special chars: <>&\"'(); -- SQL-sensitive chars.\n"
                + "End of payload.";

        NotificationDto created = notificationService.create(
                "JOB_FIRED", "Test Notification Title", largeBody,
                "JOB", "test-job-" + UUID.randomUUID().toString().substring(0, 8));

        assertThat(created).isNotNull();
        assertThat(created.id()).isNotNull();
        assertThat(created.isRead()).isFalse();

        // GET list via REST — verify seeded notification and TEXT body round-trip
        @SuppressWarnings("unchecked")
        ResponseEntity<Map> listResponse = restTemplate.getForEntity(
                "/api/v1/aria/notifications", Map.class);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).isNotNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) listResponse.getBody().get("content");
        Map<String, Object> found = content.stream()
                .filter(n -> created.id().equals(n.get("id")))
                .findFirst().orElseThrow();
        assertThat(found.get("body")).isEqualTo(largeBody);

        // GET count via REST — verify unread count
        ResponseEntity<Map> countResponse = restTemplate.getForEntity(
                "/api/v1/aria/notifications/count", Map.class);
        assertThat(countResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(countResponse.getBody()).isNotNull();
        assertThat(((Number) countResponse.getBody().get("unreadCount")).longValue()).isGreaterThanOrEqualTo(1);

        // Mark one read via service
        // NOTE: PATCH not tested via REST — JDK HttpURLConnection does not support PATCH.
        // NotificationControllerTest is @Disabled (act-aria has no @SpringBootApplication).
        // PATCH endpoints verified at service layer; documented as known tech debt.
        NotificationDto markedRead = notificationService.markRead(created.id());
        assertThat(markedRead).isNotNull();
        assertThat(markedRead.isRead()).isTrue();

        // Mark all read via service (same PATCH limitation)
        notificationService.markAllRead();

        // Verify count is now 0 via REST
        ResponseEntity<Map> finalCount = restTemplate.getForEntity(
                "/api/v1/aria/notifications/count", Map.class);
        assertThat(finalCount.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) finalCount.getBody().get("unreadCount")).longValue()).isEqualTo(0);
    }

    @Test
    void scheduledJobCrud() {
        // notificationBody exceeds VARCHAR(255) to prove TEXT column usage after @Lob removal
        String largeNotificationBody = "B".repeat(260)
                + "\nScheduled job notification body with unicode: 🚀 任务通知\n"
                + "Multi-line persistence proof for TEXT column.\n"
                + "Chars: <>&\"'(); -- SQL injection safe test.\n"
                + "End of job payload.";

        // POST create job with large notificationBody via REST (verifies TEXT persistence)
        Map<String, Object> jobRequest = Map.of(
                "scheduleType", "CRON",
                "category", "REMINDER",
                "title", "Test Scheduled Job",
                "scheduleExpression", "0 0 * * * *",
                "notificationTitle", "Job Notification",
                "notificationBody", largeNotificationBody
        );
        ResponseEntity<Map> createResponse = restTemplate.postForEntity(
                "/api/v1/aria/jobs", jobRequest, Map.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(createResponse.getBody()).isNotNull();
        assertThat(createResponse.getBody().get("id")).isNotNull();
        assertThat(createResponse.getBody().get("status")).isEqualTo("ACTIVE");
        // Verify TEXT field round-trip through REST
        assertThat(createResponse.getBody().get("notificationBody")).isEqualTo(largeNotificationBody);

        String jobId = (String) createResponse.getBody().get("id");

        // GET list via REST — verify created job appears
        ResponseEntity<List> listResponse = restTemplate.getForEntity(
                "/api/v1/aria/jobs", List.class);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).isNotNull();
        assertThat(listResponse.getBody()).isNotEmpty();

        // GET list with status filter via REST
        ResponseEntity<List> filterResponse = restTemplate.getForEntity(
                "/api/v1/aria/jobs?status=ACTIVE", List.class);
        assertThat(filterResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Pause via service (PATCH not supported by JDK HttpURLConnection)
        var pausedJob = scheduledJobService.pause(jobId);
        assertThat(pausedJob.status()).isEqualTo("PAUSED");

        // Resume via service
        var resumedJob = scheduledJobService.resume(jobId);
        assertThat(resumedJob.status()).isEqualTo("ACTIVE");

        // PUT update via REST
        Map<String, Object> updateRequest = Map.of(
                "title", "Updated Job Title",
                "notificationBody", "Updated body content"
        );
        ResponseEntity<Map> updateResponse = restTemplate.exchange(
                RequestEntity.put(URI.create("/api/v1/aria/jobs/" + jobId))
                        .body(updateRequest), Map.class);
        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updateResponse.getBody().get("title")).isEqualTo("Updated Job Title");
        assertThat(updateResponse.getBody().get("notificationBody")).isEqualTo("Updated body content");

        // DELETE via REST
        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                RequestEntity.delete(URI.create("/api/v1/aria/jobs/" + jobId)).build(),
                Void.class);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }
}
