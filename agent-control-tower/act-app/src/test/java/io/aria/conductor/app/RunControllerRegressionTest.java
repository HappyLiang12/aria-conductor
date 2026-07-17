package io.aria.conductor.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(NoopLlmTestConfig.class)
class RunControllerRegressionTest extends BaseH2IntegrationTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void listRuns_shouldReturn200() {
        var response = restTemplate.getForEntity("/api/v1/runs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getRun_shouldReturn404_forUnknownId() {
        var response = restTemplate.getForEntity("/api/v1/runs/" + UUID.randomUUID(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}


