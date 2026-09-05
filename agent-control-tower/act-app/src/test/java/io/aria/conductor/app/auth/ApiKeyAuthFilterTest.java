package io.aria.conductor.app.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ApiKeyAuthFilter}: enabled/disabled behaviour, the path
 * allow-list, malformed/missing/wrong-token 401s, constant-time compare, exempt
 * OPTIONS/health, and no credential leakage in error bodies or logs.
 */
class ApiKeyAuthFilterTest {

    private static final String KEY = "super-secret-operator-key";

    private ApiKeyAuthProperties enabledProperties() {
        ApiKeyAuthProperties properties = new ApiKeyAuthProperties();
        properties.setApiKey(KEY);
        return properties;
    }

    private ApiKeyAuthFilter filter(ApiKeyAuthProperties properties) {
        return new ApiKeyAuthFilter(properties, new ObjectMapper());
    }

    private final RecordingChain chain = new RecordingChain();

    private MockHttpServletRequest request(String method, String uri, String authHeader) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        if (authHeader != null) {
            request.addHeader(ApiKeyAuthFilter.AUTHORIZATION_HEADER, authHeader);
        }
        return request;
    }

    private MockHttpServletResponse invoke(ApiKeyAuthFilter filter, MockHttpServletRequest request)
            throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void disabledModeLetsProtectedRequestsThroughWithoutCredentials() throws Exception {
        ApiKeyAuthFilter filter = filter(new ApiKeyAuthProperties());

        MockHttpServletResponse response = invoke(filter, request("GET", "/api/v1/agents", null));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.called).isTrue();
    }

    @Test
    void validBearerTokenLetsProtectedRequestThrough() throws Exception {
        ApiKeyAuthFilter filter = filter(enabledProperties());

        MockHttpServletResponse response = invoke(
                filter, request("GET", "/api/v1/agents", "Bearer " + KEY));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.called).isTrue();
    }

    @Test
    void missingTokenReturns401JsonAndNeverReachesTheChain() throws Exception {
        ApiKeyAuthFilter filter = filter(enabledProperties());

        MockHttpServletResponse response = invoke(filter, request("GET", "/api/v1/agents", null));

        assertThat(chain.called).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(ApiKeyAuthFilter.WWW_AUTHENTICATE_HEADER))
                .isEqualTo("Bearer realm=\"aria-conductor\"");
        assertThat(response.getContentType()).contains("application/json");
        Map<String, Object> body = responseBody(response);
        assertThat(body).containsEntry("status", 401)
                .containsEntry("error", "Unauthorized")
                .containsEntry("message", "Invalid or missing API key");
        assertThat(body).containsKey("timestamp");
    }

    @Test
    void wrongTokenReturns401AndDoesNotEchoCredentialsInBody() throws Exception {
        ApiKeyAuthFilter filter = filter(enabledProperties());
        String wrongToken = "wrong-token-value";

        MockHttpServletResponse response = invoke(
                filter, request("GET", "/api/v1/agents", "Bearer " + wrongToken));

        assertThat(chain.called).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        String rawBody = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(rawBody).doesNotContain(KEY);
        assertThat(rawBody).doesNotContain(wrongToken);
        assertThat(rawBody).doesNotContain("Bearer " + wrongToken);
    }

    @Test
    void emptyTokenReturns401() throws Exception {
        ApiKeyAuthFilter filter = filter(enabledProperties());

        MockHttpServletResponse response = invoke(filter, request("GET", "/api/v1/agents", "Bearer "));

        assertThat(chain.called).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void nonBearerSchemeReturns401() throws Exception {
        ApiKeyAuthFilter filter = filter(enabledProperties());

        MockHttpServletResponse response = invoke(
                filter, request("GET", "/api/v1/agents", "Basic " + KEY));

        assertThat(chain.called).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void wrongTokenWithDifferentLengthStillReturns401() throws Exception {
        ApiKeyAuthFilter filter = filter(enabledProperties());

        MockHttpServletResponse response = invoke(
                filter, request("GET", "/api/v1/agents", "Bearer short"));

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void constantTimeComparisonAcceptsExactKeyAndRejectsLookalikes() throws Exception {
        ApiKeyAuthFilter filter = filter(enabledProperties());
        char[] nearKey = KEY.toCharArray();
        nearKey[nearKey.length - 1] = nearKey[nearKey.length - 1] == 'y' ? 'x' : 'y';

        assertThat(invoke(filter, request("GET", "/api/v1/agents", "Bearer " + KEY)).getStatus())
                .isEqualTo(200);
        assertThat(invoke(filter, request("GET", "/api/v1/agents", "Bearer " + new String(nearKey))).getStatus())
                .isEqualTo(401);
    }

    @Test
    void healthEndpointIsExemptWhenAuthEnabled() throws Exception {
        ApiKeyAuthFilter filter = filter(enabledProperties());

        MockHttpServletResponse response = invoke(filter, request("GET", "/actuator/health", null));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.called).isTrue();
    }

    @Test
    void optionsPreflightIsExemptWhenAuthEnabled() throws Exception {
        ApiKeyAuthFilter filter = filter(enabledProperties());

        MockHttpServletResponse response = invoke(filter, request("OPTIONS", "/api/v1/agents", null));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.called).isTrue();
    }

    @Test
    void nonHealthActuatorEndpointsAreProtected() throws Exception {
        ApiKeyAuthFilter filter = filter(enabledProperties());

        MockHttpServletResponse response = invoke(filter, request("GET", "/actuator/info", null));

        assertThat(chain.called).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void nonProtectedPathsPassThroughWhenAuthEnabled() throws Exception {
        ApiKeyAuthFilter filter = filter(enabledProperties());

        for (String path : List.of("/h2-console", "/h2-console/login.do",
                "/v3/api-docs", "/swagger-ui/index.html", "/ws/events", "/", "/some-page")) {
            chain.called = false;
            MockHttpServletResponse response = invoke(filter, request("GET", path, null));
            assertThat(response.getStatus()).as("path %s should pass through", path).isEqualTo(200);
            assertThat(chain.called).as("path %s should reach the chain", path).isTrue();
        }
    }

    @Test
    void unauthorizedRejectionLogsCorrelationIdButNeverTheKeyOrHeader() throws Exception {
        ApiKeyAuthFilter filter = filter(enabledProperties());
        CaptureLogAppender appender = CaptureLogAppender.attach(ApiKeyAuthFilter.class);
        try {
            MockHttpServletRequest request = request("GET", "/api/v1/agents", "Bearer leaked-token");
            request.addHeader(ApiKeyAuthFilter.CORRELATION_ID_HEADER, "corr-123");

            invoke(filter, request);

            String joined = String.join(" ", appender.messages());
            assertThat(joined).contains("corr-123");
            assertThat(joined).contains("/api/v1/agents");
            assertThat(joined).doesNotContain(KEY);
            assertThat(joined).doesNotContain("leaked-token");
            assertThat(joined).doesNotContain("Authorization");
        } finally {
            appender.detach();
        }
    }

    private Map<String, Object> responseBody(MockHttpServletResponse response) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = new ObjectMapper()
                .readValue(response.getContentAsString(StandardCharsets.UTF_8), Map.class);
        return body;
    }

    private static final class RecordingChain implements FilterChain {
        private boolean called;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            called = true;
        }
    }
}
