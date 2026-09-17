package io.aria.conductor.mcp;

import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.approval.RunScopedCredentialService;
import io.aria.conductor.execution.approval.WriteGrantService;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * C4 ruling 3 + 11: the worker/operator boundary over the REAL streamable-HTTP
 * transport (the endpoint opencode's remote MCP client uses). This is also the
 * identity-propagation proof: the bearer is resolved by the servlet filter into
 * a ThreadLocal holder and must still be visible when the tool bean is invoked
 * inside the MCP SDK's session handling — a lost identity would classify the
 * worker as OPERATOR and the grant-less write below would succeed instead of
 * being denied.
 *
 * <p>Token mode, so the operator bearer ("test-token-1") keeps full access and
 * every other bearer must be a live worker credential.
 */
@SpringBootTest(classes = McpTestBootstrap.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "aria.mcp.enabled=true",
        "aria.mcp.auth-mode=token",
        "aria.mcp.token=test-token-1"
})
@Import({RunScopedCredentialService.class, WriteGrantService.class})
class McpWorkerEndpointIntegrationTest {

    private static final String OPERATOR_TOKEN = "test-token-1";
    private static final UUID RUN_ID = UUID.fromString("0a0a0a0a-1111-2222-3333-444455556666");
    private static final UUID AGENT_ID = UUID.fromString("0b0b0b0b-1111-2222-3333-444455556666");
    private static final UUID OTHER_RUN_ID = UUID.fromString("0c0c0c0c-1111-2222-3333-444455556666");

    @LocalServerPort
    int port;

    @Autowired
    RunScopedCredentialService credentials;

    @Autowired
    WriteGrantService writeGrants;

    @MockitoBean io.aria.conductor.knowledge.service.WorkflowTemplateService workflowTemplateService;
    @MockitoBean io.aria.conductor.agent.service.WorkflowService workflowService;
    @MockitoBean io.aria.conductor.execution.mcp.McpProperties mcpProperties;
    @MockitoBean io.aria.conductor.knowledge.service.KnowledgeService knowledgeService;
    @MockitoBean io.aria.conductor.execution.approval.ApprovalQueryService approvalQueryService;
    @MockitoBean io.aria.conductor.execution.approval.ApprovalGate approvalGate;
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

    String workerToken;

    @BeforeEach
    void setUp() {
        when(mcpProperties.isTokenMode()).thenReturn(true);
        when(mcpProperties.getToken()).thenReturn(OPERATOR_TOKEN);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(Run.builder()
                .id(RUN_ID).agentId(AGENT_ID).status(RunStatus.RUNNING).build()));
        when(runRepository.findById(OTHER_RUN_ID)).thenReturn(Optional.of(Run.builder()
                .id(OTHER_RUN_ID).agentId(AGENT_ID).status(RunStatus.RUNNING).build()));
        workerToken = credentials.issue(RUN_ID, Instant.now().plus(Duration.ofMinutes(10)));
    }

    private McpSyncClient client(String bearer) {
        HttpClientStreamableHttpTransport.Builder builder =
                HttpClientStreamableHttpTransport.builder("http://localhost:" + port).endpoint("/mcp");
        if (bearer != null) {
            builder.customizeRequest(request -> request.header("Authorization", "Bearer " + bearer));
        }
        return McpClient.sync(builder.build()).requestTimeout(Duration.ofSeconds(15)).build();
    }

    /** Calls a tool and returns the surfaced error text (denial code included). */
    private static String callExpectingDenial(McpSyncClient client, String tool, Map<String, Object> args) {
        try {
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(tool, args));
            assertThat(result.isError()).as("tool %s must be denied", tool).isTrue();
            StringBuilder text = new StringBuilder();
            result.content().forEach(content -> {
                if (content instanceof McpSchema.TextContent textContent) {
                    text.append(textContent.text());
                }
            });
            return text.toString();
        } catch (McpError error) {
            return error.getMessage();
        }
    }

    private static String okText(McpSchema.CallToolResult result) {
        assertThat(result.isError()).isFalse();
        StringBuilder text = new StringBuilder();
        result.content().forEach(content -> {
            if (content instanceof McpSchema.TextContent textContent) {
                text.append(textContent.text());
            }
        });
        return text.toString();
    }

    // ── worker: reads pass, unapproved writes are denied ────────────────────

    @Test
    void workerToken_allowedRead_passes() {
        try (McpSyncClient client = client(workerToken)) {
            client.initialize();

            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest("list_knowledge", Map.of()));

            assertThat(okText(result)).contains("\"ok\":true");
            verify(knowledgeService).listKnowledge(null, null);
        }
    }

    @Test
    void workerToken_writeWithoutGrant_isDenied_andTheServiceIsUntouched() {
        try (McpSyncClient client = client(workerToken)) {
            client.initialize();

            String error = callExpectingDenial(client, "generate_report", Map.of(
                    "title", "worker summary", "description", "must not be generated"));

            assertThat(error).contains("GRANT_REQUIRED").contains("generate_report");
            // Identity propagation proof: with a lost/absent identity the aspect would treat the
            // caller as operator and this write would have reached the service.
            verifyNoInteractions(reportService);
        }
    }

    @Test
    void workerToken_writeWithGrant_succeedsOnce_andTheReplayIsDenied() {
        // The approval side (C2/C3) digests the FULL named-argument map, nulls preserved.
        Map<String, Object> approved = new LinkedHashMap<>();
        approved.put("title", "worker summary");
        approved.put("description", "approved work");
        approved.put("dataScope", null);
        approved.put("owner", null);
        approved.put("sourceRunId", null);
        approved.put("sensitivity", null);
        writeGrants.grant(RUN_ID, "generate_report", WriteGrantService.argsDigest(approved));

        try (McpSyncClient client = client(workerToken)) {
            client.initialize();

            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest("generate_report",
                    Map.of("title", "worker summary", "description", "approved work")));

            assertThat(okText(result)).contains("\"ok\":true");
            verify(reportService).generate(any());

            String replay = callExpectingDenial(client, "generate_report",
                    Map.of("title", "worker summary", "description", "approved work"));
            assertThat(replay).contains("GRANT_REQUIRED");
        }
    }

    @Test
    void workerToken_grantForDifferentArgs_isDenied() {
        writeGrants.grant(RUN_ID, "generate_report", WriteGrantService.argsDigest(
                Map.of("title", "approved title", "description", "approved description")));

        try (McpSyncClient client = client(workerToken)) {
            client.initialize();

            String error = callExpectingDenial(client, "generate_report",
                    Map.of("title", "different title", "description", "approved description"));

            assertThat(error).contains("GRANT_REQUIRED");
            verifyNoInteractions(reportService);
        }
    }

    // ── worker: pinned operator-only denials ────────────────────────────────

    @Test
    void workerToken_operatorOnlyTools_areDenied() {
        try (McpSyncClient client = client(workerToken)) {
            client.initialize();

            assertThat(callExpectingDenial(client, "decide_approval",
                    Map.of("approvalId", UUID.randomUUID().toString(), "approved", true)))
                    .contains("OPERATOR_ONLY");
            assertThat(callExpectingDenial(client, "retire_agent",
                    Map.of("id", AGENT_ID.toString())))
                    .contains("OPERATOR_ONLY");
            assertThat(callExpectingDenial(client, "run_agent",
                    Map.of("agentId", AGENT_ID.toString(), "prompt", "ignored")))
                    .contains("OPERATOR_ONLY");
            assertThat(callExpectingDenial(client, "housekeeping_execute",
                    Map.of("categories", java.util.List.of("runs"), "includeStuck", false, "confirm", true)))
                    .contains("OPERATOR_ONLY");
            assertThat(callExpectingDenial(client, "create_llm_provider",
                    Map.of("name", "n", "type", "OPENAI", "apiKey", "test-key-value")))
                    .contains("OPERATOR_ONLY");

            verifyNoInteractions(approvalGate, agentService, runService, housekeepingService, llmProviderService);
        }
    }

    // ── worker: scope binding ───────────────────────────────────────────────

    @Test
    void workerToken_foreignRunIdOnGetRun_isDenied_andOwnRunIdIsAllowed() {
        try (McpSyncClient client = client(workerToken)) {
            client.initialize();

            String error = callExpectingDenial(client, "get_run",
                    Map.of("id", OTHER_RUN_ID.toString()));
            assertThat(error).contains("SCOPE_MISMATCH");
            verifyNoInteractions(runService);

            McpSchema.CallToolResult own = client.callTool(new McpSchema.CallToolRequest("get_run",
                    Map.of("id", RUN_ID.toString())));
            assertThat(okText(own)).contains("\"ok\":true");
        }
    }

    @Test
    void workerToken_foreignAgentIdOnGetAgent_isDenied() {
        try (McpSyncClient client = client(workerToken)) {
            client.initialize();

            String error = callExpectingDenial(client, "get_agent",
                    Map.of("id", UUID.randomUUID().toString()));

            assertThat(error).contains("SCOPE_MISMATCH");
            verifyNoInteractions(agentService);
        }
    }

    // ── operator token unchanged ────────────────────────────────────────────

    @Test
    void operatorToken_keepsFullAccess_acrossAllCategories() {
        when(agentService.retireAgent(AGENT_ID)).thenReturn(null);

        try (McpSyncClient client = client(OPERATOR_TOKEN)) {
            client.initialize();

            McpSchema.CallToolResult read = client.callTool(
                    new McpSchema.CallToolRequest("list_knowledge", Map.of()));
            assertThat(okText(read)).contains("\"ok\":true");

            // Operator-only, scope-mismatching and unregistered-by-workers calls all pass.
            McpSchema.CallToolResult retire = client.callTool(new McpSchema.CallToolRequest("retire_agent",
                    Map.of("id", AGENT_ID.toString())));
            assertThat(okText(retire)).contains("\"ok\":true");
            verify(agentService).retireAgent(AGENT_ID);

            McpSchema.CallToolResult foreignRun = client.callTool(new McpSchema.CallToolRequest("get_run",
                    Map.of("id", OTHER_RUN_ID.toString())));
            assertThat(okText(foreignRun)).contains("\"ok\":true");
        }
    }

    // ── identity rejection and discovery residual ───────────────────────────

    @Test
    void unknownBearer_isRejectedAtTheTransport() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer wcp_test_unknown")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,"
                        + "\"method\":\"initialize\",\"params\":{}}"))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void toolsList_isNotFilteredPerIdentity_ruling10Residual() {
        // Ruling 10: per-request tools/list filtering needs a second server built per identity
        // (McpServerConfig builds ONE MethodToolCallbackProvider + ONE McpSyncServer from all
        // McpTool beans at startup), so discovery stays unfiltered and call-time enforcement is
        // the boundary. This test pins that residual explicitly.
        try (McpSyncClient client = client(workerToken)) {
            client.initialize();

            McpSchema.ListToolsResult tools = client.listTools();

            assertThat(tools.tools())
                    .extracting(McpSchema.Tool::name)
                    .containsExactlyInAnyOrderElementsOf(McpToolInventory.EXPECTED_TOOL_NAMES);
            assertThat(tools.tools())
                    .extracting(McpSchema.Tool::name)
                    .contains("decide_approval", "retire_agent", "housekeeping_execute");
        }
    }
}
