package io.aria.conductor.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §3 fallback handshake proof: opencode's remote MCP client speaks
 * streamable HTTP ONLY (live-verified: 404 on /mcp against the SSE-only
 * server, no SSE fallback), so this test connects the MCP java SDK's
 * streamable CLIENT transport to /mcp and asserts tool parity with the SSE
 * anchor ({@link McpEndpointIntegrationTest}).
 */
@SpringBootTest(classes = McpTestBootstrap.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "aria.mcp.enabled=true",
        "aria.mcp.auth-mode=none"
})
@Import({io.aria.conductor.execution.approval.RunScopedCredentialService.class,
        io.aria.conductor.execution.approval.WriteGrantService.class})
class McpStreamableEndpointIntegrationTest {

    @LocalServerPort
    int port;

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

    private McpSyncClient streamableClient() {
        return McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                        .endpoint("/mcp")
                        .build())
                .requestTimeout(java.time.Duration.ofSeconds(10))
                .build();
    }

    @Test
    void streamableHandshake_listsSameCuratedTools() {
        try (McpSyncClient client = streamableClient()) {
            client.initialize();
            McpSchema.ListToolsResult tools = client.listTools();

            assertThat(tools.tools())
                    .extracting(McpSchema.Tool::name)
                    .containsExactlyInAnyOrderElementsOf(McpToolInventory.EXPECTED_TOOL_NAMES);
        }
    }

    @Test
    void streamableHandshake_callToolReturnsOkResult() {
        try (McpSyncClient client = streamableClient()) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest("list_knowledge", java.util.Map.of()));

            assertThat(result.isError()).isFalse();
            assertThat(((McpSchema.TextContent) result.content().get(0)).text()).contains("\"ok\":true");
        }
    }
}
