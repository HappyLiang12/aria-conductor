package io.aria.conductor.mcp;

import io.aria.conductor.execution.mcp.McpProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class McpTokenFilterTest {

    private McpProperties props(String mode, String token) {
        McpProperties p = new McpProperties();
        p.setAuthMode(mode);
        p.setToken(token);
        return p;
    }

    @Test
    void tokenMode_validBearer_passes() throws Exception {
        McpTokenFilter filter = new McpTokenFilter(props("token", "secret-1"));
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        req.addHeader("Authorization", "Bearer secret-1");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res,
                (request, response) -> ((HttpServletResponse) response).setStatus(200));

        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void tokenMode_missingHeader_401() throws Exception {
        McpTokenFilter filter = new McpTokenFilter(props("token", "secret-1"));
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res,
                (request, response) -> ((HttpServletResponse) response).setStatus(200));

        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void tokenMode_wrongPrefix_401() throws Exception {
        McpTokenFilter filter = new McpTokenFilter(props("token", "secret-1"));
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        req.addHeader("Authorization", "Basic c2VjcmV0LTE=");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res,
                (request, response) -> ((HttpServletResponse) response).setStatus(200));

        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void tokenMode_nonMcpPath_untouched() throws Exception {
        McpTokenFilter filter = new McpTokenFilter(props("token", "secret-1"));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/agents");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res,
                (request, response) -> ((HttpServletResponse) response).setStatus(200));

        assertThat(res.getStatus()).isEqualTo(200);
    }
}
