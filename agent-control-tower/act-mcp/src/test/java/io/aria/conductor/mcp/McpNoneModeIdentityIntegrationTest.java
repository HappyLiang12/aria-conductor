package io.aria.conductor.mcp;

import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.approval.RunScopedCredentialService;
import io.aria.conductor.execution.approval.WriteGrantService;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * C4 ruling 2 for auth-mode=none: a request without an Authorization header
 * stays operator-equivalent (v1 behaviour unchanged), while a header that is
 * present must now be a live worker credential — anything else is 401. That
 * rejection is a deliberate fail-closed change (previously headers were ignored
 * in none mode) and is flagged in task-C4-report.md.
 *
 * <p>Worker classification also works in none mode, so a sandbox can be handed
 * a scoped credential without switching the whole endpoint to token mode.
 */
@SpringBootTest(classes = McpTestBootstrap.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "aria.mcp.enabled=true",
        "aria.mcp.auth-mode=none"
})
@Import({RunScopedCredentialService.class, WriteGrantService.class})
class McpNoneModeIdentityIntegrationTest {

    private static final UUID RUN_ID = UUID.fromString("0d0d0d0d-1111-2222-3333-444455556666");
    private static final UUID AGENT_ID = UUID.fromString("0e0e0e0e-1111-2222-3333-444455556666");

    @LocalServerPort
    int port;

    @Autowired
    RunScopedCredentialService credentials;

    @MockitoBean io.aria.conductor.knowledge.service.WorkflowTemplateService workflowTemplateService;
    @MockitoBean io.aria.conductor.agent.service.WorkflowService workflowService;
    @MockitoBean io.aria.conductor.execution.mcp.McpProperties mcpProperties;
    @MockitoBean io.aria.conductor.knowledge.service.KnowledgeService knowledgeService;
    @MockitoBean io.aria.conductor.execution.approval.ApprovalQueryService approvalQueryService;
    @MockitoBean io.aria.conductor.execution.approval.ApprovalGate approvalGate;
    @MockitoBean io.aria.conductor.execution.approval.ApprovalDecisionService approvalDecisionService;
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
        when(mcpProperties.isTokenMode()).thenReturn(false);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(Run.builder()
                .id(RUN_ID).agentId(AGENT_ID).status(RunStatus.RUNNING).build()));
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

    @Test
    void noHeader_isOperatorEquivalent_fullAccessUnchanged() {
        try (McpSyncClient client = client(null)) {
            client.initialize();

            // Operator-only tool, no grant, no bearer: none-mode behaviour is unchanged.
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest("retire_agent",
                    Map.of("id", AGENT_ID.toString())));

            assertThat(okText(result)).contains("\"ok\":true");
            verify(agentService).retireAgent(AGENT_ID);
        }
    }

    @Test
    void validWorkerBearer_isClassifiedAsWorker_inNoneModeToo() {
        try (McpSyncClient client = client(workerToken)) {
            client.initialize();

            McpSchema.CallToolResult read = client.callTool(
                    new McpSchema.CallToolRequest("list_knowledge", Map.of()));
            assertThat(okText(read)).contains("\"ok\":true");

            McpSchema.CallToolResult write = client.callTool(new McpSchema.CallToolRequest("generate_report",
                    Map.of("title", "t", "description", "d")));
            assertThat(write.isError()).isTrue();
            assertThat(((McpSchema.TextContent) write.content().get(0)).text()).contains("GRANT_REQUIRED");
            verifyNoInteractions(reportService);
        }
    }

    @Test
    void presentButUnknownBearer_is401_failClosedChange() throws Exception {
        assertThat(rawMcpStatus("Bearer wcp_test_unknown")).isEqualTo(401);
    }

    @Test
    void staleOperatorToken_is401_inNoneMode() throws Exception {
        // isOperatorBearer is mode-independent: with a real ARIA_MCP_TOKEN configured, that
        // bearer would be admitted as OPERATOR even in none mode. This test passes because the
        // mocked McpProperties returns a null token (the production default is blank), so the
        // present-but-unresolvable bearer is rejected 401.
        assertThat(rawMcpStatus("Bearer test-token-1")).isEqualTo(401);
    }

    private int rawMcpStatus(String authorization) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", authorization)
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,"
                        + "\"method\":\"initialize\",\"params\":{}}"))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }
}
