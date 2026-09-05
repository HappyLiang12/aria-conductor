package io.aria.conductor.app.security;

import io.aria.conductor.ActApplication;
import io.aria.conductor.app.NoopLlmTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live-boot verification of the API-key gate (AC1 live-boot coverage, AC2, AC3, AC4).
 *
 * <p>Boots the real application with {@code app.security.enabled=true} and a mixed plaintext +
 * {@code sha256:} key set, then asserts enforcement on a representative sample of controllers,
 * anonymous access to the actuator endpoints, successful auth over both header conventions, and
 * rejection of wrong/absent/malformed credentials.
 */
@SpringBootTest(
        classes = {ActApplication.class, NoopLlmTestConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.security.enabled=true",
                "app.security.api-keys=" + ApiKeySecurityIntegrationTest.PLAIN_KEY
                        + ", sha256:19f57cefc32dc0e9d7dc245dec59c706a8bc5570ca42d8bd5faf944f982654eb"
        })
@ActiveProfiles({"test", "noop-llm"})
@Sql(scripts = "classpath:db/cleanup-all.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class ApiKeySecurityIntegrationTest {

    static final String PLAIN_KEY = "integration-plain-key";
    static final String HASHED_KEY = "integration-hashed-key";
    static final String WRONG_KEY = "integration-wrong-key";

    private static final List<String> PROTECTED_GET_ENDPOINTS = List.of(
            "/api/v1/agents",
            "/api/v1/workflows",
            "/api/v1/runs",
            "/api/v1/tools",
            "/api/v1/skills",
            "/api/v1/knowledge",
            "/api/v1/llm-providers",
            "/api/v1/dashboard/summary",
            "/api/v1/approvals");

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void everyProtectedEndpointRejectsMissingCredentialsWith401() {
        for (String path : PROTECTED_GET_ENDPOINTS) {
            ResponseEntity<String> response = restTemplate.exchange(
                    path, HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
            assertThat(response.getStatusCode())
                    .as("unauthorized request to %s", path)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertUnauthorizedBody(response);
            assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                    .as("WWW-Authenticate on %s", path)
                    .isEqualTo("Bearer");
        }
    }

    @Test
    void protectedWriteActionsAlsoRejectMissingCredentialsWith401() {
        UUID id = UUID.randomUUID();
        // Resume action on a run.
        ResponseEntity<String> resume = restTemplate.exchange(
                "/api/v1/runs/" + id + "/resume", HttpMethod.POST,
                new HttpEntity<>(new HttpHeaders()), String.class);
        assertThat(resume.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertUnauthorizedBody(resume);

        // Approve/deny decision on an approval.
        ResponseEntity<String> decide = restTemplate.exchange(
                "/api/v1/approvals/" + id + "/decide", HttpMethod.POST,
                new HttpEntity<>("{\"approved\":true}", jsonHeaders()), String.class);
        assertThat(decide.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertUnauthorizedBody(decide);
    }

    @Test
    void singleAgentResourceRejectsMissingCredentialsWith401() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/agents/" + UUID.randomUUID(), HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertUnauthorizedBody(response);
    }

    @Test
    void actuatorHealthAndInfoRemainAnonymous() {
        // AC2: /actuator/health and /actuator/info stay reachable without credentials when auth is
        // enabled. The aggregate health status may legitimately be DOWN in this test environment
        // (no local ADK runtime), so the point under test is that the auth gate does not intercept
        // the request (which would surface as 401/403 + a WWW-Authenticate challenge).
        HttpHeaders anonymous = new HttpHeaders();
        ResponseEntity<String> health = restTemplate.exchange(
                "/actuator/health", HttpMethod.GET, new HttpEntity<>(anonymous), String.class);
        assertThat(health.getStatusCode()).isNotIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        assertThat(health.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isNull();

        ResponseEntity<String> info = restTemplate.exchange(
                "/actuator/info", HttpMethod.GET, new HttpEntity<>(anonymous), String.class);
        assertThat(info.getStatusCode()).isNotIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        assertThat(info.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isNull();
    }

    @Test
    void validKeyViaBearerHeaderReachesControllers() {
        for (String path : PROTECTED_GET_ENDPOINTS) {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(PLAIN_KEY);
            ResponseEntity<String> response = restTemplate.exchange(
                    path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            assertThat(response.getStatusCode())
                    .as("authenticated request to %s", path)
                    .isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void validKeyViaXApiKeyHeaderReachesControllers() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", PLAIN_KEY);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/agents", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void hashedConfiguredKeyAcceptsItsPreimage() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(HASHED_KEY);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/agents", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void wrongKeyIsRejectedWith401() {
        HttpHeaders bearer = new HttpHeaders();
        bearer.setBearerAuth(WRONG_KEY);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/agents", HttpMethod.GET, new HttpEntity<>(bearer), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertUnauthorizedBody(response);
    }

    @Test
    void malformedCredentialsAreRejectedWith401() {
        // Bare Bearer with no token.
        HttpHeaders bare = new HttpHeaders();
        bare.set(HttpHeaders.AUTHORIZATION, "Bearer");
        ResponseEntity<String> bareResponse = restTemplate.exchange(
                "/api/v1/agents", HttpMethod.GET, new HttpEntity<>(bare), String.class);
        assertThat(bareResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertUnauthorizedBody(bareResponse);

        // Non-Bearer Authorization scheme.
        HttpHeaders basic = new HttpHeaders();
        basic.set(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz");
        ResponseEntity<String> basicResponse = restTemplate.exchange(
                "/api/v1/agents", HttpMethod.GET, new HttpEntity<>(basic), String.class);
        assertThat(basicResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertUnauthorizedBody(basicResponse);
    }

    @Test
    void authorizedRequestsReachHandlersForNestedActions() {
        // Unknown agent id: reaching the controller yields 404 (not 401), proving the gate passed.
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(PLAIN_KEY);
        ResponseEntity<String> missing = restTemplate.exchange(
                "/api/v1/agents/" + UUID.randomUUID(), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Unknown run resume: 404 rather than 401.
        ResponseEntity<String> resume = restTemplate.exchange(
                "/api/v1/runs/" + UUID.randomUUID() + "/resume", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);
        assertThat(resume.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static void assertUnauthorizedBody(ResponseEntity<String> response) {
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).contains("\"error\":\"unauthorized\"");
        // No internal detail, stack traces or data leaks in the error body.
        assertThat(response.getBody()).doesNotContain("Exception", "Trace", "at io.aria");
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
