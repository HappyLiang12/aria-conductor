package io.aria.conductor.app.auth;

import io.aria.conductor.app.BaseH2IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the full application with {@code ARIA_API_KEY} configured and asserts the
 * 401 (no/invalid token) and authenticated 200 paths end-to-end, plus the exempt
 * {@code /actuator/health} probe and 401-for-unknown-protected-path precedence.
 */
@TestPropertySource(properties = {
        "app.auth.api-key=" + ApiKeyAuthIntegrationTest.TEST_KEY,
        // Deterministic /actuator/health (real h2/mariadb profiles disable the sandbox
        // indicator; without a container runtime it would otherwise report DOWN -> 503).
        "sandbox.health.enabled=false"
})
class ApiKeyAuthIntegrationTest extends BaseH2IntegrationTest {

    static final String TEST_KEY = "integration-test-operator-key";

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void unauthenticatedApiRequestReturns401WithErrorSchema() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/api/v1/agents", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst("WWW-Authenticate"))
                .isEqualTo("Bearer realm=\"aria-conductor\"");
        assertThat(response.getBody()).containsEntry("status", 401)
                .containsEntry("error", "Unauthorized")
                .containsEntry("message", "Invalid or missing API key");
        assertThat(response.getBody()).containsKey("timestamp");
    }

    @Test
    void unauthenticatedRequestToUnknownProtectedPathIs401Not404() {
        ResponseEntity<Map> response =
                restTemplate.getForEntity("/api/v1/no-such-endpoint-xyz", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void validBearerTokenSucceedsOnApiEndpoints() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TEST_KEY);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        for (String path : new String[] {
                "/api/v1/agents",
                "/api/v1/dashboard/summary",
                "/api/v1/workflows",
                "/api/v1/knowledge?type=WORKFLOW" }) {
            ResponseEntity<String> response =
                    restTemplate.exchange(path, HttpMethod.GET, entity, String.class);
            assertThat(response.getStatusCode()).as("GET %s", path)
                    .isIn(HttpStatus.OK, HttpStatus.NO_CONTENT);
        }
    }

    @Test
    void wrongTokenReturns401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("not-the-configured-key");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<Map> response =
                restTemplate.exchange("/api/v1/agents", HttpMethod.GET, entity, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void actuatorHealthRemainsOpenWithoutToken() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/actuator/health", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
    }
}
