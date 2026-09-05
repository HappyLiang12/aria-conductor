package io.aria.conductor.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
class McpStreamableEndpointIntegrationTest {

    @LocalServerPort
    int port;

    @MockitoBean io.aria.conductor.knowledge.service.WorkflowTemplateService workflowTemplateService;
    @MockitoBean io.aria.conductor.agent.service.WorkflowService workflowService;
    @MockitoBean io.aria.conductor.execution.mcp.McpProperties mcpProperties;
    @MockitoBean io.aria.conductor.knowledge.service.KnowledgeService knowledgeService;
    @MockitoBean io.aria.conductor.execution.approval.ApprovalQueryService approvalQueryService;
    @MockitoBean io.aria.conductor.execution.approval.ApprovalGate approvalGate;

    private McpSyncClient streamableClient() {
        return McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                        .endpoint("/mcp")
                        .build())
                .requestTimeout(java.time.Duration.ofSeconds(10))
                .build();
    }

    @Test
    void streamableHandshake_listsSameCuratedPhase2Tools() {
        try (McpSyncClient client = streamableClient()) {
            client.initialize();
            McpSchema.ListToolsResult tools = client.listTools();

            assertThat(tools.tools())
                    .extracting(McpSchema.Tool::name)
                    .containsExactlyInAnyOrder(
                            "list_workflow_templates",
                            "instantiate_workflow_template",
                            "get_workflow",
                            "list_knowledge",
                            "list_approvals",
                            "decide_approval");
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
