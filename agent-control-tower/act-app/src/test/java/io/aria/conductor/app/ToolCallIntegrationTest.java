package io.aria.conductor.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallIntegrationTest extends BaseH2IntegrationTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void getToolCallsEndpoint_returns200() {
        // The endpoint should return 200 even when the run UUID doesn't exist
        // (Flyway-managed H2 with no matching rows returns an empty result)
        String runId = UUID.randomUUID().toString();
        ResponseEntity<List> response = restTemplate.getForEntity(
                "/api/v1/runs/" + runId + "/tool-calls", List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void getToolCallsEndpoint_returnsEmptyForUnknownRun() {
        // Use a random UUID that definitely doesn't match any run
        ResponseEntity<List> response = restTemplate.getForEntity(
                "/api/v1/runs/" + UUID.randomUUID() + "/tool-calls", List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).isEmpty();
    }
}
