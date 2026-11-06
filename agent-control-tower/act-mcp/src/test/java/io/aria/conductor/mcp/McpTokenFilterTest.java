package io.aria.conductor.mcp;

import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.execution.security.ActorTokenService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpTokenFilterTest {

    private static final UUID RUN_A = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID RUN_B = UUID.fromString("00000000-0000-0000-0000-000000000202");

    private McpProperties props(String mode, String token) {
        McpProperties p = new McpProperties();
        p.setAuthMode(mode);
        p.setToken(token);
        return p;
    }

    @SuppressWarnings("unchecked")
    private static McpTokenFilter actorModeFilter(ActorTokenService actorTokens) {
        McpProperties properties = new McpProperties();
        properties.setAuthMode("actor");
        ObjectProvider<ActorTokenService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(actorTokens);
        return new McpTokenFilter(properties, provider);
    }

    private static MockHttpServletResponse dispatch(McpTokenFilter filter, MockHttpServletRequest request)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response,
                (req, res) -> ((HttpServletResponse) res).setStatus(200));
        return response;
    }

    private static MockHttpServletRequest sseMessagePost(String token, String querySessionId,
                                                         String headerSessionId) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp/message");
        request.addParameter("sessionId", querySessionId);
        request.addHeader("Authorization", "Bearer " + token);
        if (headerSessionId != null) {
            request.addHeader("Mcp-Session-Id", headerSessionId);
        }
        return request;
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

    @Test
    void actorMode_sseMessageEndpoint_decoyHeaderCannotBindAnotherRunsSession() throws Exception {
        ActorTokenService tokens = new ActorTokenService();
        String tokenB = tokens.issueWorker(RUN_B, Instant.now().plus(Duration.ofMinutes(10)));
        McpTokenFilter filter = actorModeFilter(tokens);

        // victim sessionId in the query, fresh decoy header, run B's token
        MockHttpServletRequest bypass = sseMessagePost(tokenB, "victim-run-a-session", "decoy-fresh-value");

        assertThat(dispatch(filter, bypass).getStatus()).isEqualTo(403);
    }

    @Test
    void actorMode_sseMessageEndpoint_bindsTheQueryParameterTheSdkDispatchesOn() throws Exception {
        ActorTokenService tokens = new ActorTokenService();
        String tokenA = tokens.issueWorker(RUN_A, Instant.now().plus(Duration.ofMinutes(10)));
        String tokenB = tokens.issueWorker(RUN_B, Instant.now().plus(Duration.ofMinutes(10)));
        McpTokenFilter filter = actorModeFilter(tokens);

        // run A's first message binds session-9 (header and query agree); run B reusing
        // the query parameter alone is rejected against that binding
        assertThat(dispatch(filter, sseMessagePost(tokenA, "session-9", "session-9")).getStatus()).isEqualTo(200);
        assertThat(dispatch(filter, sseMessagePost(tokenB, "session-9", null)).getStatus()).isEqualTo(403);
    }

    @Test
    void actorMode_streamableHeaderBinding_stillWorks() throws Exception {
        ActorTokenService tokens = new ActorTokenService();
        String tokenA = tokens.issueWorker(RUN_A, Instant.now().plus(Duration.ofMinutes(10)));
        String tokenB = tokens.issueWorker(RUN_B, Instant.now().plus(Duration.ofMinutes(10)));
        McpTokenFilter filter = actorModeFilter(tokens);

        MockHttpServletRequest first = new MockHttpServletRequest("POST", "/mcp");
        first.addHeader("Authorization", "Bearer " + tokenA);
        first.addHeader("Mcp-Session-Id", "streamable-session-1");
        MockHttpServletRequest sameActor = new MockHttpServletRequest("POST", "/mcp");
        sameActor.addHeader("Authorization", "Bearer " + tokenA);
        sameActor.addHeader("Mcp-Session-Id", "streamable-session-1");
        MockHttpServletRequest otherActor = new MockHttpServletRequest("POST", "/mcp");
        otherActor.addHeader("Authorization", "Bearer " + tokenB);
        otherActor.addHeader("Mcp-Session-Id", "streamable-session-1");

        assertThat(dispatch(filter, first).getStatus()).isEqualTo(200);
        assertThat(dispatch(filter, sameActor).getStatus()).isEqualTo(200);
        assertThat(dispatch(filter, otherActor).getStatus()).isEqualTo(403);
    }
}
