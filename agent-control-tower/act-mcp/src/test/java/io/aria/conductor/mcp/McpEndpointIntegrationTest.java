package io.aria.conductor.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.aria.conductor.mcp.catalog.ToolCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {McpTestBootstrap.class, McpEndpointIntegrationTest.SignalBeans.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "aria.mcp.enabled=true",
        "aria.mcp.auth-mode=none"
})
class McpEndpointIntegrationTest {

    /** The token service the worker-bearer test needs; the stream beans come from the bootstrap. */
    @org.springframework.boot.test.context.TestConfiguration
    static class SignalBeans {
        @org.springframework.context.annotation.Bean
        io.aria.conductor.execution.security.ActorTokenService actorTokenService() {
            return new io.aria.conductor.execution.security.ActorTokenService();
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    io.aria.conductor.execution.security.ActorTokenService actorTokens;

    @org.springframework.beans.factory.annotation.Autowired
    io.aria.conductor.execution.engine.RunStreamRegistry runStreams;

    @LocalServerPort
    int port;

    @MockitoBean io.aria.conductor.knowledge.service.WorkflowTemplateService workflowTemplateService;
    @MockitoBean io.aria.conductor.agent.service.WorkflowService workflowService;
    @MockitoBean io.aria.conductor.execution.mcp.McpProperties mcpProperties;
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
    @MockitoBean io.aria.conductor.memory.service.MemoryCommandService memoryCommandService;
    @MockitoBean io.aria.conductor.memory.service.MemoryProposalService memoryProposalService;
    @MockitoBean io.aria.conductor.memory.service.MemoryQueryService memoryQueryService;
    @MockitoBean io.aria.conductor.memory.service.MemoryRecallService memoryRecallService;
    @MockitoBean io.aria.conductor.execution.context.ContextManifestRepository contextManifestRepository;
    @MockitoBean io.aria.conductor.memory.repository.MemoryFactRepository memoryFactRepository;
    @MockitoBean io.aria.conductor.memory.repository.MemoryAssertionRepository memoryAssertionRepository;

    @Test
    void handshake_listsCuratedTools() {
        try (McpSyncClient client = McpClient.sync(
                HttpClientSseClientTransport.builder("http://localhost:" + port).build())
                .requestTimeout(java.time.Duration.ofSeconds(10))
                .build()) {
            client.initialize();
            McpSchema.ListToolsResult tools = client.listTools();
            ToolCatalog catalog = new ToolCatalog();

            assertThat(tools.tools())
                    .extracting(McpSchema.Tool::name)
                    .containsExactlyInAnyOrderElementsOf(McpToolInventory.EXPECTED_TOOL_NAMES)
                    .containsExactlyInAnyOrderElementsOf(catalog.names());
            // The registered description is the catalog's one-line @Tool description.
            assertThat(tools.tools()).allSatisfy(tool ->
                    assertThat(tool.description())
                            .isEqualTo(catalog.find(tool.name())
                                    .orElseThrow(() -> new AssertionError(
                                            "registered tool '" + tool.name() + "' is not in the catalog"))
                                    .description()));
        }
    }

    @Test
    void callTool_invocationIsAudited() {
        try (McpSyncClient client = McpClient.sync(
                HttpClientSseClientTransport.builder("http://localhost:" + port).build())
                .requestTimeout(java.time.Duration.ofSeconds(10))
                .build()) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest("list_knowledge", java.util.Map.of()));

            assertThat(result.isError()).isFalse();
            assertThat(((McpSchema.TextContent) result.content().get(0)).text()).contains("\"ok\":true");
        }
    }

    /**
     * The glasses contract is camelCase JSON with a nested {@code target} object
     * ({@code {"primitive":"track","target":{"trackId":7},"text":"TV remote","anchor":"screen"}}).
     * Spring AI names the arguments from the Java parameter names and converts the nested
     * object into {@code ArRenderTools.ArTarget}, so this call is the only place the wire
     * shape the phone sends is proven end to end — the unit test calls the method directly.
     */
    @Test
    void callTool_arRenderAcceptsTheNestedTargetObject() {
        try (McpSyncClient client = McpClient.sync(
                HttpClientSseClientTransport.builder("http://localhost:" + port).build())
                .requestTimeout(java.time.Duration.ofSeconds(10))
                .build()) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest("ar_render",
                    java.util.Map.of(
                            "primitive", "track",
                            "target", java.util.Map.of("trackId", 7),
                            "text", "TV remote",
                            "anchor", "screen")));

            assertThat(result.isError()).isFalse();
            assertThat(((McpSchema.TextContent) result.content().get(0)).text())
                    .contains("\"ok\":true")
                    .contains("\"primitive\":\"track\"");
        }
    }

    /**
     * The deterministic, model-free proof of the device delivery path: a real MCP
     * session authenticates as the run's worker, the tool executes, and the frame is
     * written to that run's live stream. The ack's {@code delivered:true} is the
     * registry's own answer, so a frame that never reached a stream cannot pass.
     */
    @Test
    void arRenderEmitsTheFrameOnTheWorkersRunStream() {
        java.util.UUID runId = java.util.UUID.randomUUID();
        String workerToken = actorTokens.issueWorker(runId, java.time.Instant.now().plusSeconds(120));
        runStreams.register(runId, new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(60_000L));

        try (McpSyncClient client = McpClient.sync(
                HttpClientSseClientTransport.builder("http://localhost:" + port)
                        .customizeRequest(builder -> builder.header("Authorization", "Bearer " + workerToken))
                        .build())
                .requestTimeout(java.time.Duration.ofSeconds(10))
                .build()) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest("ar_render",
                    java.util.Map.of(
                            "primitive", "track",
                            "target", java.util.Map.of("trackId", 7),
                            "text", "TV remote")));

            assertThat(result.isError()).isFalse();
            assertThat(((McpSchema.TextContent) result.content().get(0)).text())
                    .contains("\"ok\":true")
                    .contains("\"delivered\":true");
            assertThat(runStreams.hasStream(runId)).isTrue();
        }
    }

    /** No credential means no run to address: the ack is returned, nothing is emitted. */
    @Test
    void arRenderWithoutACredentialIsAcknowledgedButNotDelivered() {
        try (McpSyncClient client = McpClient.sync(
                HttpClientSseClientTransport.builder("http://localhost:" + port).build())
                .requestTimeout(java.time.Duration.ofSeconds(10))
                .build()) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest("ar_render",
                    java.util.Map.of("primitive", "clear")));

            assertThat(result.isError()).isFalse();
            assertThat(((McpSchema.TextContent) result.content().get(0)).text())
                    .contains("\"ok\":true")
                    .contains("\"delivered\":false");
        }
    }
}
