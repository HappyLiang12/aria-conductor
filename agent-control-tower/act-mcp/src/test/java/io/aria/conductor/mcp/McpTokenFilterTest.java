package io.aria.conductor.mcp;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.approval.RunScopedCredentialService;
import io.aria.conductor.execution.mcp.McpProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C4 ruling 3: identity resolution at the transport seam, in BOTH auth modes.
 * Token mode keeps the frozen operator comparison byte-for-byte; a bearer that
 * is neither the operator token nor a live worker credential is rejected with
 * 401. None mode keeps "no header is operator-equivalent" and gains the
 * deliberate fail-closed change: a present-but-invalid bearer is 401.
 *
 * <p>All tokens here are synthetic; nothing real is logged or printed.
 */
class McpTokenFilterTest {

    private static final String OPERATOR_TOKEN = "op-test-token-1";

    private final RunRepository runRepository = mock(RunRepository.class);
    private final RunScopedCredentialService credentials = new RunScopedCredentialService(runRepository);

    private McpProperties props(String mode, String token) {
        McpProperties p = new McpProperties();
        p.setAuthMode(mode);
        p.setToken(token);
        return p;
    }

    private McpTokenFilter filter(String mode, String token) {
        McpProperties p = props(mode, token);
        return new McpTokenFilter(p, new WorkerScopeResolver(p, credentials));
    }

    private UUID runningRun() {
        UUID runId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        when(runRepository.findById(runId)).thenReturn(Optional.of(Run.builder()
                .id(runId).agentId(agentId).status(RunStatus.RUNNING).build()));
        return runId;
    }

    private String issueWorkerToken(UUID runId) {
        return credentials.issue(runId, Instant.now().plus(Duration.ofMinutes(5)));
    }

    /** Runs the filter and returns what the chain observed as the request identity. */
    private Optional<McpCallerContext.Caller> runFilter(McpTokenFilter filter, MockHttpServletRequest req,
                                                        MockHttpServletResponse res) throws Exception {
        AtomicReference<Optional<McpCallerContext.Caller>> seen = new AtomicReference<>(Optional.empty());
        filter.doFilterInternal(req, res, (request, response) -> {
            seen.set(McpCallerContext.current());
            ((HttpServletResponse) response).setStatus(200);
        });
        return seen.get();
    }

    // ── token mode: frozen operator path ────────────────────────────────────

    @Test
    void tokenMode_validOperatorBearer_passesAsOperator() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        req.addHeader("Authorization", "Bearer " + OPERATOR_TOKEN);
        MockHttpServletResponse res = new MockHttpServletResponse();

        Optional<McpCallerContext.Caller> caller = runFilter(filter("token", OPERATOR_TOKEN), req, res);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(caller.orElseThrow().kind()).isEqualTo(McpCallerContext.Kind.OPERATOR);
        assertThat(McpCallerContext.current()).isEmpty();
    }

    @Test
    void tokenMode_missingHeader_401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        MockHttpServletResponse res = new MockHttpServletResponse();

        Optional<McpCallerContext.Caller> caller = runFilter(filter("token", OPERATOR_TOKEN), req, res);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(caller).isEmpty();
        assertThat(McpCallerContext.current()).isEmpty();
    }

    @Test
    void tokenMode_wrongPrefixIsNotAMatch_401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        req.addHeader("Authorization", "Basic c2VjcmV0LTE=");
        MockHttpServletResponse res = new MockHttpServletResponse();

        runFilter(filter("token", OPERATOR_TOKEN), req, res);

        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void tokenMode_nonMcpPath_untouched() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/agents");
        MockHttpServletResponse res = new MockHttpServletResponse();

        runFilter(filter("token", OPERATOR_TOKEN), req, res);

        assertThat(res.getStatus()).isEqualTo(200);
        // Identity is only bound on the MCP surface.
        assertThat(McpCallerContext.current()).isEmpty();
    }

    // ── token mode: worker credentials ──────────────────────────────────────

    @Test
    void tokenMode_validWorkerBearer_passesAsWorkerWithItsScope() throws Exception {
        UUID runId = runningRun();
        String workerToken = issueWorkerToken(runId);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        req.addHeader("Authorization", "Bearer " + workerToken);
        MockHttpServletResponse res = new MockHttpServletResponse();

        Optional<McpCallerContext.Caller> caller = runFilter(filter("token", OPERATOR_TOKEN), req, res);

        assertThat(res.getStatus()).isEqualTo(200);
        McpCallerContext.Caller seen = caller.orElseThrow();
        assertThat(seen.kind()).isEqualTo(McpCallerContext.Kind.WORKER);
        assertThat(seen.scope().runId()).isEqualTo(runId);
        assertThat(McpCallerContext.current()).isEmpty();
    }

    @Test
    void tokenMode_unknownBearer_401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        req.addHeader("Authorization", "Bearer wcp_test_unknown");
        MockHttpServletResponse res = new MockHttpServletResponse();

        Optional<McpCallerContext.Caller> caller = runFilter(filter("token", OPERATOR_TOKEN), req, res);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(caller).isEmpty();
    }

    @Test
    void revokedWorkerToken_401() throws Exception {
        UUID runId = runningRun();
        String workerToken = issueWorkerToken(runId);
        credentials.revoke(runId);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        req.addHeader("Authorization", "Bearer " + workerToken);
        MockHttpServletResponse res = new MockHttpServletResponse();

        runFilter(filter("token", OPERATOR_TOKEN), req, res);

        assertThat(res.getStatus()).isEqualTo(401);
    }

    // ── none mode ───────────────────────────────────────────────────────────

    @Test
    void noneMode_noHeader_isOperatorEquivalent() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        MockHttpServletResponse res = new MockHttpServletResponse();

        Optional<McpCallerContext.Caller> caller = runFilter(filter("none", ""), req, res);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(caller.orElseThrow().kind()).isEqualTo(McpCallerContext.Kind.OPERATOR);
        assertThat(McpCallerContext.current()).isEmpty();
    }

    @Test
    void noneMode_validWorkerBearer_isClassifiedAsWorker() throws Exception {
        UUID runId = runningRun();
        String workerToken = issueWorkerToken(runId);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        req.addHeader("Authorization", "Bearer " + workerToken);
        MockHttpServletResponse res = new MockHttpServletResponse();

        Optional<McpCallerContext.Caller> caller = runFilter(filter("none", ""), req, res);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(caller.orElseThrow().kind()).isEqualTo(McpCallerContext.Kind.WORKER);
    }

    @Test
    void noneMode_presentButInvalidBearer_401_failClosedChange() throws Exception {
        // Deliberate C4 change: an Authorization header must now be a real worker credential,
        // even though none-mode requests without a header stay open.
        MockHttpServletRequest unknown = new MockHttpServletRequest("POST", "/mcp");
        unknown.addHeader("Authorization", "Bearer wcp_test_unknown");
        MockHttpServletResponse unknownRes = new MockHttpServletResponse();
        runFilter(filter("none", ""), unknown, unknownRes);
        assertThat(unknownRes.getStatus()).isEqualTo(401);

        // A blank configured operator token must never authenticate a "Bearer " header.
        MockHttpServletRequest blank = new MockHttpServletRequest("POST", "/mcp");
        blank.addHeader("Authorization", "Bearer ");
        MockHttpServletResponse blankRes = new MockHttpServletResponse();
        runFilter(filter("none", ""), blank, blankRes);
        assertThat(blankRes.getStatus()).isEqualTo(401);

        // The operator token is not active in none mode either: 401, not silent operator access.
        MockHttpServletRequest stale = new MockHttpServletRequest("POST", "/mcp");
        stale.addHeader("Authorization", "Bearer " + OPERATOR_TOKEN);
        MockHttpServletResponse staleRes = new MockHttpServletResponse();
        runFilter(filter("none", ""), stale, staleRes);
        assertThat(staleRes.getStatus()).isEqualTo(401);
    }

    // ── transport scoping: worker identity needs the streamable endpoint ────

    @Test
    void workerCredential_onSseSurfaces_401_becauseIdentityCannotBeBoundThere() throws Exception {
        // Ruling 3: the SSE surfaces carry no per-request transport context, so a worker
        // call there could not be enforced at the tool seam. Fail closed: a live worker
        // credential is accepted ONLY on the streamable endpoint /mcp.
        UUID runId = runningRun();
        String workerToken = issueWorkerToken(runId);
        McpTokenFilter filter = filter("token", OPERATOR_TOKEN);
        for (String path : java.util.List.of("/sse", "/mcp/message")) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
            req.addHeader("Authorization", "Bearer " + workerToken);
            MockHttpServletResponse res = new MockHttpServletResponse();

            runFilter(filter, req, res);

            assertThat(res.getStatus()).as("worker bearer on %s must be rejected", path).isEqualTo(401);
        }
    }

    @Test
    void operatorBearer_onSseSurfaces_stillPassesUnchanged() throws Exception {
        McpTokenFilter filter = filter("token", OPERATOR_TOKEN);
        for (String path : java.util.List.of("/sse", "/mcp/message")) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
            req.addHeader("Authorization", "Bearer " + OPERATOR_TOKEN);
            MockHttpServletResponse res = new MockHttpServletResponse();

            Optional<McpCallerContext.Caller> caller = runFilter(filter, req, res);

            assertThat(res.getStatus()).as("operator bearer on %s", path).isEqualTo(200);
            assertThat(caller.orElseThrow().kind()).isEqualTo(McpCallerContext.Kind.OPERATOR);
        }
    }

    // ── cleanup ─────────────────────────────────────────────────────────────

    @Test
    void identityIsClearedInFinally_evenWhenTheChainThrows() throws Exception {
        UUID runId = runningRun();
        String workerToken = issueWorkerToken(runId);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        req.addHeader("Authorization", "Bearer " + workerToken);
        MockHttpServletResponse res = new MockHttpServletResponse();
        McpTokenFilter filter = filter("token", OPERATOR_TOKEN);

        assertThatThrownBy(() -> filter.doFilterInternal(req, res, (request, response) -> {
            throw new IllegalStateException("downstream failure");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(McpCallerContext.current()).isEmpty();
    }
}
