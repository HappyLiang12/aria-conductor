package io.aria.conductor.mcp;

import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.execution.security.ActorTokenService;
import io.aria.conductor.execution.security.OperatorSessionService;
import io.aria.conductor.mcp.tools.McpTool;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 4 transport proof: the authenticated actor is bound to the MCP session,
 * not to caller-supplied JSON, over BOTH real HTTP transports — the manually
 * configured streamable endpoint (/mcp, used by opencode) and the SSE endpoint
 * (/sse + /mcp/message). Every assertion here is made over the wire; the tool
 * under test is a test-only echo that resolves identity through
 * {@link McpActorContext#require(ToolContext)}.
 */
@SpringBootTest(classes = {McpTestBootstrap.class, McpActorTransportIntegrationTest.ActorBoundaryConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // literal: an annotation on this class cannot reference its own fields; the
        // verifyOperatorCredential assertion below pins the literal to OPERATOR_CREDENTIAL
        properties = {"aria.mcp.enabled=true", "aria.mcp.auth-mode=actor",
                "aria.operator.bearer-token=test-operator-credential-1"})
class McpActorTransportIntegrationTest {

    static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    static final UUID OTHER_RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000202");
    static final String TOOL_NAME = "test_who_am_i";
    static final String OPERATOR_CREDENTIAL = "test-operator-credential-1";

    private static final String INITIALIZE_BODY = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26",
            "capabilities":{},"clientInfo":{"name":"actor-boundary-raw","version":"1.0"}}}""";
    private static final String INITIALIZED_NOTIFICATION_BODY = """
            {"jsonrpc":"2.0","method":"notifications/initialized"}""";
    private static final String CALL_BODY = """
            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"test_who_am_i","arguments":{}}}""";
    /** Worker self-approval attempt over the MCP surface (Task 12 boundary). */
    private static final String DECIDE_APPROVAL_BODY = """
            {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"decide_approval",
            "arguments":{"approvalId":"00000000-0000-0000-0000-000000000303","approved":true,
            "reason":"self-approval"}}}""";

    @LocalServerPort
    int port;

    @Autowired
    ActorTokenService actorTokens;

    @Autowired
    OperatorSessionService operatorSessions;

    @Autowired
    TestRestTemplate restTemplate;

    @MockitoBean io.aria.conductor.knowledge.service.WorkflowTemplateService workflowTemplateService;
    @MockitoBean io.aria.conductor.agent.service.WorkflowService workflowService;
    @MockitoBean io.aria.conductor.knowledge.service.KnowledgeService knowledgeService;
    @MockitoBean io.aria.conductor.execution.approval.ApprovalQueryService approvalQueryService;
    @MockitoBean io.aria.conductor.execution.approval.ApprovalGate approvalGate;
    @MockitoBean io.aria.conductor.execution.approval.PermissionCoordinator permissionCoordinator;
    @MockitoBean io.aria.conductor.agent.service.AgentService agentService;
    @MockitoBean io.aria.conductor.agent.service.RunService runService;
    @MockitoBean io.aria.conductor.agent.service.LlmProviderService llmProviderService;
    @MockitoBean io.aria.conductor.agent.repository.AgentRepository agentRepository;
    @MockitoBean io.aria.conductor.agent.repository.RunRepository runRepository;
    @MockitoBean io.aria.conductor.execution.repository.ApprovalRepository approvalRepository;
    @MockitoBean io.aria.conductor.execution.repository.PromptCallRepository promptCallRepository;
    @MockitoBean io.aria.conductor.execution.repository.SessionTrajectoryRepository sessionTrajectoryRepository;
    @MockitoBean io.aria.conductor.execution.kanban.KanbanService kanbanService;
    @MockitoBean io.aria.conductor.execution.kanban.KanbanTransitionService kanbanTransitionService;
    @MockitoBean io.aria.conductor.execution.dod.DoDService dodService;
    @MockitoBean io.aria.conductor.execution.housekeeping.HousekeepingService housekeepingService;
    @MockitoBean io.aria.conductor.dashboard.report.ReportService reportService;
    @MockitoBean io.aria.conductor.knowledge.selfimprove.SkillDefinitionRepository skillDefinitionRepository;
    @MockitoBean io.aria.conductor.aria.service.NotificationService notificationService;
    @MockitoBean io.aria.conductor.aria.service.ScheduledJobService scheduledJobService;

    /**
     * Test-only tool that echoes the transport-owned identity, plus the real
     * worker-token service (production wiring scans io.aria.conductor in act-app).
     */
    @TestConfiguration
    static class ActorBoundaryConfig {

        @Bean
        ActorTokenService actorTokenService() {
            return new ActorTokenService();
        }

        /**
         * Real operator boundary wired to the configured operator credential, so the
         * "operator credentials are invalid on the worker surface" test proves a
         * boundary-valid credential — not merely an unknown string — is rejected.
         */
        @Bean
        OperatorSessionService operatorSessionService(
                @Value("${aria.operator.bearer-token:}") String operatorCredential) {
            return new OperatorSessionService(operatorCredential, Duration.ofHours(1),
                    "http://localhost:8080", false, Clock.systemUTC());
        }

        @Bean
        McpProperties mcpProperties() {
            McpProperties properties = new McpProperties();
            properties.setAuthMode("actor");
            return properties;
        }

        @Bean
        McpTool actorEchoTool() {
            return new ActorEchoTool();
        }
    }

    public static class ActorEchoTool implements McpTool {

        @Tool(name = TOOL_NAME, description = "Test-only: echo the transport-owned MCP actor identity.")
        public String whoAmI(ToolContext context) {
            ActorPrincipal actor = McpActorContext.require(context);
            return "actor=" + actor.role() + " run=" + actor.runId() + " expires=" + actor.expiresAt();
        }
    }

    // ------------------------------------------------------------------
    // identity travels with the transport
    // ------------------------------------------------------------------

    @Test
    void streamableTransport_carriesTheRunScopedActor() {
        String token = actorTokens.issueWorker(RUN_ID, Instant.now().plus(Duration.ofMinutes(10)));
        try (McpSyncClient client = streamableClient(token)) {
            client.initialize();

            String text = callEchoTool(client);

            assertThat(text).contains("actor=WORKER").contains("run=" + RUN_ID);
        }
    }

    @Test
    void concurrentStreamableSessions_doNotLeakActors() {
        String tokenA = actorTokens.issueWorker(RUN_ID, Instant.now().plus(Duration.ofMinutes(10)));
        String tokenB = actorTokens.issueWorker(OTHER_RUN_ID, Instant.now().plus(Duration.ofMinutes(10)));
        try (McpSyncClient clientA = streamableClient(tokenA);
             McpSyncClient clientB = streamableClient(tokenB)) {
            clientA.initialize();
            clientB.initialize();

            assertThat(callEchoTool(clientA)).contains("run=" + RUN_ID);
            assertThat(callEchoTool(clientB)).contains("run=" + OTHER_RUN_ID);
            assertThat(callEchoTool(clientA)).contains("run=" + RUN_ID).doesNotContain(OTHER_RUN_ID.toString());
        }
    }

    @Test
    void sseTransport_carriesTheRunScopedActor() {
        String token = actorTokens.issueWorker(RUN_ID, Instant.now().plus(Duration.ofMinutes(10)));
        try (McpSyncClient client = McpClient.sync(HttpClientSseClientTransport.builder("http://localhost:" + port)
                        .customizeRequest(builder -> builder.header("Authorization", "Bearer " + token))
                        .build())
                .requestTimeout(Duration.ofSeconds(10))
                .build()) {
            client.initialize();

            String text = callEchoTool(client);

            assertThat(text).contains("actor=WORKER").contains("run=" + RUN_ID);
        }
    }

    // ------------------------------------------------------------------
    // 401 for absent/invalid/expired/revoked identity
    // ------------------------------------------------------------------

    @Test
    void absentOrInvalidCredentials_areRejectedWith401OnBothTransports() {
        assertThat(rawPost("/mcp", null, null, INITIALIZE_BODY).getStatusCode().value()).isEqualTo(401);
        assertThat(rawPost("/mcp", "forged-token", null, INITIALIZE_BODY).getStatusCode().value()).isEqualTo(401);
        assertThat(rawPost("/mcp/message", "forged-token", "some-session", CALL_BODY).getStatusCode().value())
                .isEqualTo(401);

        ResponseEntity<String> sse = restTemplate.exchange("http://localhost:" + port + "/sse",
                HttpMethod.GET, new HttpEntity<>(null, new HttpHeaders()), String.class);
        assertThat(sse.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void expiredAndRevokedWorkerTokens_areRejectedWith401() {
        String expired = actorTokens.issueWorker(RUN_ID, Instant.now().minusSeconds(1));
        assertThat(rawPost("/mcp", expired, null, INITIALIZE_BODY).getStatusCode().value()).isEqualTo(401);

        String revoked = actorTokens.issueWorker(OTHER_RUN_ID, Instant.now().plus(Duration.ofMinutes(10)));
        actorTokens.revokeRun(OTHER_RUN_ID);
        assertThat(rawPost("/mcp", revoked, null, INITIALIZE_BODY).getStatusCode().value()).isEqualTo(401);
    }

    // ------------------------------------------------------------------
    // session binding and identity spoofing
    // ------------------------------------------------------------------

    @Test
    void mcpSessionIsBoundToItsActor_rejectsCrossActorReuse() {
        String tokenA = actorTokens.issueWorker(RUN_ID, Instant.now().plus(Duration.ofMinutes(10)));
        String tokenB = actorTokens.issueWorker(OTHER_RUN_ID, Instant.now().plus(Duration.ofMinutes(10)));

        ResponseEntity<String> initialize = rawPost("/mcp", tokenA, null, INITIALIZE_BODY);
        assertThat(initialize.getStatusCode().value()).isEqualTo(200);
        String sessionId = initialize.getHeaders().getFirst("Mcp-Session-Id");
        assertThat(sessionId).isNotBlank();

        ResponseEntity<String> sameActor = rawPost("/mcp", tokenA, sessionId, CALL_BODY);
        assertThat(sameActor.getStatusCode().value()).isEqualTo(200);
        assertThat(sameActor.getBody()).contains("run=" + RUN_ID).doesNotContain(OTHER_RUN_ID.toString());

        ResponseEntity<String> otherActor = rawPost("/mcp", tokenB, sessionId, CALL_BODY);
        assertThat(otherActor.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void suppliedIdentityArguments_areRejectedAndNeverReplaceTheTransportActor() {
        String token = actorTokens.issueWorker(RUN_ID, Instant.now().plus(Duration.ofMinutes(10)));

        try (McpSyncClient client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                        .endpoint("/mcp")
                        .customizeRequest(builder -> builder.header("Authorization", "Bearer " + token))
                        .build())
                .requestTimeout(Duration.ofSeconds(10))
                .build()) {
            client.initialize();

            // a forged `_actor` claim is rejected before the tool runs
            McpSchema.CallToolResult spoofed = client.callTool(new McpSchema.CallToolRequest(TOOL_NAME,
                    Map.of("_actor", "OPERATOR:" + OTHER_RUN_ID)));
            assertThat(spoofed.isError()).isTrue();
            assertThat(((McpSchema.TextContent) spoofed.content().get(0)).text())
                    .contains("transport-owned");

            // a plain runId argument is a business parameter: identity stays the token's run
            McpSchema.CallToolResult runIdClaim = client.callTool(new McpSchema.CallToolRequest(TOOL_NAME,
                    Map.of("runId", OTHER_RUN_ID.toString())));
            assertThat(runIdClaim.isError()).isFalse();
            assertThat(((McpSchema.TextContent) runIdClaim.content().get(0)).text()).contains("run=" + RUN_ID);
        }
    }

    // ------------------------------------------------------------------
    // SSE message endpoint: the installed SDK reads sessionId from the query
    // parameter only, so the filter's binding must never be evadable with a
    // decoy Mcp-Session-Id header.
    // ------------------------------------------------------------------

    @Test
    @Timeout(60)
    void sseMessageEndpoint_decoyHeaderCannotBindAnotherRunsSession() throws Exception {
        String tokenA = actorTokens.issueWorker(RUN_ID, Instant.now().plus(Duration.ofMinutes(10)));
        String tokenB = actorTokens.issueWorker(OTHER_RUN_ID, Instant.now().plus(Duration.ofMinutes(10)));

        // Open run A's SSE stream (kept open: closing it tears the session down) and
        // read the message-endpoint sessionId the SDK publishes in its `endpoint` event.
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<Stream<String>> stream = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl() + "/sse"))
                            .header("Authorization", "Bearer " + tokenA)
                            .header("Accept", "text/event-stream")
                            .timeout(Duration.ofSeconds(30))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofLines());
            assertThat(stream.statusCode()).isEqualTo(200);
            String victimSessionId = sseSessionId(stream.body());
            try {
                // run A's session handshake, exactly like the SDK SSE client: initialize, the
                // initialized notification (the SDK releases the exchange sink on it; anything
                // earlier would block the SDK's message handler forever), then a tool call
                assertThat(sseMessage(tokenA, victimSessionId, INITIALIZE_BODY, Map.of()).getStatusCode().value())
                        .isEqualTo(200);
                assertThat(sseMessage(tokenA, victimSessionId, INITIALIZED_NOTIFICATION_BODY, Map.of())
                        .getStatusCode().value()).isEqualTo(200);
                assertThat(sseMessage(tokenA, victimSessionId, CALL_BODY, Map.of()).getStatusCode().value())
                        .isEqualTo(200);

                // the bypass: a victim sessionId in the query, a fresh decoy header, run B's token
                ResponseEntity<String> bypass = sseMessage(tokenB, victimSessionId, CALL_BODY,
                        Map.of("Mcp-Session-Id", "decoy-" + UUID.randomUUID()));
                assertThat(bypass.getStatusCode().value()).isEqualTo(403);

                // reusing the victim session by query parameter alone is rejected as well
                assertThat(sseMessage(tokenB, victimSessionId, CALL_BODY, Map.of()).getStatusCode().value())
                        .isEqualTo(403);

                // and run A's own session is untouched by the attempts
                assertThat(sseMessage(tokenA, victimSessionId, CALL_BODY, Map.of()).getStatusCode().value())
                        .isEqualTo(200);
            } finally {
                stream.body().close();
            }
        }
    }

    /**
     * The operator credential authenticates on the MCP surface. The
     * mcp-into-opencode design (§7.5) has an external client drive operator actions
     * there, and the tool policy's OPERATOR_ONLY class is only reachable through this
     * branch — a surface that knew only worker tokens would make that class
     * unreachable. What stays shut is the worker→operator direction, pinned by the
     * cases below: a run-scoped worker token calling an operator tool is FORBIDDEN.
     */
    @Test
    void operatorCredential_authenticatesOnTheMcpSurface() throws Exception {
        assertThat(operatorSessions.isConfigured())
                .as("operator credential configured for this context").isTrue();
        assertThat(operatorSessions.verifyOperatorCredential("Bearer " + OPERATOR_CREDENTIAL))
                .as("credential is valid at the operator boundary")
                .isTrue();

        assertThat(rawPost("/mcp", OPERATOR_CREDENTIAL, null, INITIALIZE_BODY).getStatusCode().value())
                .as("the operator credential opens the streamable transport")
                .isEqualTo(200);
        assertThat(rawGetStatus("/sse", OPERATOR_CREDENTIAL))
                .as("the operator credential opens the SSE transport")
                .isEqualTo(200);
        // The message endpoint authenticates the same way: a bogus session id is a
        // session error, never an authentication refusal.
        assertThat(rawPost("/mcp/message", OPERATOR_CREDENTIAL, "some-session", CALL_BODY).getStatusCode().value())
                .isNotEqualTo(401);
    }

    // ------------------------------------------------------------------
    // Task 12: the MCP approval boundary — a run-scoped worker can never
    // self-approve, on either transport.
    // ------------------------------------------------------------------

    /**
     * Streamable transport (the endpoint opencode negotiates): a worker token
     * calling {@code decide_approval} gets the FORBIDDEN tool error and the
     * permission coordinator is never asked.
     */
    @Test
    void streamableTransport_workerSelfApprovalIsForbiddenAndReachesNoCoordinator() {
        String token = actorTokens.issueWorker(RUN_ID, Instant.now().plus(Duration.ofMinutes(10)));
        try (McpSyncClient client = streamableClient(token)) {
            client.initialize();

            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(
                    "decide_approval", Map.of(
                    "approvalId", "00000000-0000-0000-0000-000000000303",
                    "approved", true,
                    "reason", "self-approval")));

            assertThat(result.isError()).isFalse();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("\"ok\":false")
                    .contains("\"errorType\":\"FORBIDDEN\"")
                    .contains("\"message\":\"Operator authority required\"");
        }
        org.mockito.Mockito.verify(permissionCoordinator, org.mockito.Mockito.never())
                .decide(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    /**
     * SSE transport: the same tool callback is invoked and refused; a live echo
     * call on the same authenticated session proves the request was handled as a
     * tool call rather than rejected at the transport.
     */
    @Test
    @Timeout(60)
    void sseTransport_workerSelfApprovalIsRefusedByTheToolBoundary() throws Exception {
        String token = actorTokens.issueWorker(RUN_ID, Instant.now().plus(Duration.ofMinutes(10)));
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<Stream<String>> stream = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl() + "/sse"))
                            .header("Authorization", "Bearer " + token)
                            .header("Accept", "text/event-stream")
                            .timeout(Duration.ofSeconds(30))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofLines());
            assertThat(stream.statusCode()).isEqualTo(200);
            String sessionId = sseSessionId(stream.body());
            try {
                assertThat(sseMessage(token, sessionId, INITIALIZE_BODY, Map.of()).getStatusCode().value())
                        .isEqualTo(200);
                assertThat(sseMessage(token, sessionId, INITIALIZED_NOTIFICATION_BODY, Map.of())
                        .getStatusCode().value()).isEqualTo(200);

                // the worker's self-approval attempt is handled (200) and refused inside the tool
                assertThat(sseMessage(token, sessionId, DECIDE_APPROVAL_BODY, Map.of())
                        .getStatusCode().value()).isEqualTo(200);
                org.mockito.Mockito.verify(permissionCoordinator, org.mockito.Mockito.never())
                        .decide(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.any());

                // the same live session still executes an ordinary tool call
                assertThat(sseMessage(token, sessionId, CALL_BODY, Map.of()).getStatusCode().value())
                        .isEqualTo(200);
            } finally {
                stream.body().close();
            }
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    /** Reads the sessionId the SSE provider publishes in its first `endpoint` event. */
    private static String sseSessionId(Stream<String> lines) {
        Iterator<String> iterator = lines.iterator();
        while (iterator.hasNext()) {
            String line = iterator.next();
            int marker = line.indexOf("sessionId=");
            if (marker >= 0) {
                return line.substring(marker + "sessionId=".length()).split("[^A-Za-z0-9_-]", 2)[0];
            }
        }
        throw new AssertionError("SSE endpoint event carrying sessionId never arrived");
    }

    private ResponseEntity<String> sseMessage(String token, String sessionId, String body,
                                              Map<String, String> extraHeaders) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));
        headers.setBearerAuth(token);
        extraHeaders.forEach(headers::set);
        return restTemplate.exchange(baseUrl() + "/mcp/message?sessionId=" + sessionId,
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    /**
     * Bounded GET status probe: {@code ofInputStream} returns once the response head
     * arrives and never awaits the (possibly endless) SSE body.
     */
    private int rawGetStatus(String path, String token) throws IOException, InterruptedException {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<InputStream> response = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl() + path))
                            .header("Authorization", "Bearer " + token)
                            .header("Accept", "text/event-stream")
                            .timeout(Duration.ofSeconds(10))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            response.body().close();
            return response.statusCode();
        }
    }

    private String callEchoTool(McpSyncClient client) {
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(TOOL_NAME, Map.of()));
        assertThat(result.isError()).isFalse();
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    private McpSyncClient streamableClient(String token) {
        return McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                        .endpoint("/mcp")
                        .customizeRequest(builder -> builder.header("Authorization", "Bearer " + token))
                        .build())
                .requestTimeout(Duration.ofSeconds(10))
                .build();
    }

    private ResponseEntity<String> rawPost(String path, String token, String sessionId, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (sessionId != null) {
            headers.set("Mcp-Session-Id", sessionId);
        }
        return restTemplate.exchange("http://localhost:" + port + path, HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }
}
