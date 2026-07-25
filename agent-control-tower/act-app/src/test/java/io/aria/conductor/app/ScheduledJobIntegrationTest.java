package io.aria.conductor.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REST journey over the act-aria scheduled-job endpoints:
 * create -> list -> pause/resume (toggle) -> update -> delete, asserting the persisted
 * fields round-trip through the DTO on every hop. Uses a far-off 6-field cron (03:00
 * daily) so the job never fires during the test.
 * <p>
 * PATCH requests go through a JDK-HttpClient-backed RestTemplate because the default
 * TestRestTemplate request factory (HttpURLConnection) cannot send PATCH.
 */
@Import(NoopLlmTestConfig.class)
class ScheduledJobIntegrationTest extends BaseH2IntegrationTest {

    private static final String JOBS = "/api/v1/aria/jobs";

    @Autowired
    TestRestTemplate restTemplate;

    @LocalServerPort
    int port;

    // ---- helpers ----

    /** RestTemplate that supports PATCH and never throws on error statuses. */
    private RestTemplate patchClient() {
        RestTemplate rt = new RestTemplate(new JdkClientHttpRequestFactory());
        rt.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }

            @Override
            public void handleError(ClientHttpResponse response) {
            }
        });
        return rt;
    }

    private ResponseEntity<Map> patch(String path) {
        return patchClient().exchange(
                "http://localhost:" + port + path, HttpMethod.PATCH, null, Map.class);
    }

    private Map<String, Object> createJob(String title) {
        Map<String, Object> request = new HashMap<>();
        request.put("scheduleType", "CRON");
        request.put("category", "REMINDER");
        request.put("title", title);
        request.put("scheduleExpression", "0 0 3 * * *");
        request.put("notificationTitle", "Nightly check");
        request.put("notificationBody", "Time to review the queue");
        ResponseEntity<Map> response = restTemplate.postForEntity(JOBS, request, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private List<Map<String, Object>> listJobs(String query) {
        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                JOBS + query, HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private Map<String, Object> findById(List<Map<String, Object>> jobs, String id) {
        return jobs.stream().filter(j -> id.equals(j.get("id"))).findFirst().orElse(null);
    }

    // ==================== full CRUD + toggle journey ====================

    @Test
    void createListToggleUpdateDelete_persistsEveryTransition() {
        String title = "job-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> created = createJob(title);
        String id = (String) created.get("id");

        // Create: server assigns the id, forces ACTIVE, echoes the persisted fields
        assertThat(id).isNotNull();
        assertThat(created.get("status")).isEqualTo("ACTIVE");
        assertThat(created.get("scheduleType")).isEqualTo("CRON");
        assertThat(created.get("category")).isEqualTo("REMINDER");
        assertThat(created.get("title")).isEqualTo(title);
        assertThat(created.get("scheduleExpression")).isEqualTo("0 0 3 * * *");
        assertThat(created.get("notificationTitle")).isEqualTo("Nightly check");
        assertThat(created.get("notificationBody")).isEqualTo("Time to review the queue");
        assertThat(created.get("createdAt")).isNotNull();
        assertThat(created.get("lastFiredAt")).as("job must not have fired yet").isNull();

        // List: visible unfiltered and via category/status filters
        assertThat(findById(listJobs(""), id)).isNotNull();
        assertThat(findById(listJobs("?category=REMINDER&status=ACTIVE"), id)).isNotNull();

        // Pause (disable)
        ResponseEntity<Map> paused = patch(JOBS + "/" + id + "/pause");
        assertThat(paused.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(paused.getBody().get("status")).isEqualTo("PAUSED");
        assertThat(findById(listJobs("?status=PAUSED"), id)).isNotNull();
        assertThat(findById(listJobs("?status=ACTIVE"), id)).isNull();

        // Resume
        ResponseEntity<Map> resumed = patch(JOBS + "/" + id + "/resume");
        assertThat(resumed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resumed.getBody().get("status")).isEqualTo("ACTIVE");

        // Update title only: other fields must be preserved
        ResponseEntity<Map> updated = restTemplate.exchange(
                JOBS + "/" + id, HttpMethod.PUT,
                new HttpEntity<>(Map.of("title", title + "-renamed")), Map.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().get("title")).isEqualTo(title + "-renamed");
        assertThat(updated.getBody().get("category")).isEqualTo("REMINDER");
        assertThat(updated.getBody().get("scheduleExpression")).isEqualTo("0 0 3 * * *");
        assertThat(updated.getBody().get("status")).isEqualTo("ACTIVE");

        // Update persisted, not just echoed
        Map<String, Object> listed = findById(listJobs(""), id);
        assertThat(listed.get("title")).isEqualTo(title + "-renamed");

        // Delete
        ResponseEntity<Void> deleted = restTemplate.exchange(
                JOBS + "/" + id, HttpMethod.DELETE, null, Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(findById(listJobs(""), id)).isNull();
    }

    // ==================== negative paths ====================

    @Test
    void pause_unknownJob_isAnError() {
        ResponseEntity<Map> response = patch(JOBS + "/" + UUID.randomUUID() + "/pause");
        assertThat(response.getStatusCode().isError()).isTrue();
    }

    @Test
    void update_unknownJob_isAnError() {
        ResponseEntity<Map> response = restTemplate.exchange(
                JOBS + "/" + UUID.randomUUID(), HttpMethod.PUT,
                new HttpEntity<>(Map.of("title", "ghost")), Map.class);
        assertThat(response.getStatusCode().isError()).isTrue();
    }
}
