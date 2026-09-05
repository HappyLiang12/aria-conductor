package io.aria.conductor.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = McpTestBootstrap.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "aria.mcp.enabled=true",
        "aria.mcp.auth-mode=none"
})
class McpEndpointIntegrationTest {

    @LocalServerPort
    int port;

    @MockitoBean io.aria.conductor.knowledge.service.WorkflowTemplateService workflowTemplateService;
    @MockitoBean io.aria.conductor.agent.service.WorkflowService workflowService;
    @MockitoBean io.aria.conductor.execution.mcp.McpProperties mcpProperties;
    @MockitoBean io.aria.conductor.knowledge.service.KnowledgeService knowledgeService;
    @MockitoBean io.aria.conductor.execution.approval.ApprovalQueryService approvalQueryService;
    @MockitoBean io.aria.conductor.execution.approval.ApprovalGate approvalGate;

    @Test
    void handshake_listsCuratedPhase2Tools() {
        try (McpSyncClient client = McpClient.sync(
                HttpClientSseClientTransport.builder("http://localhost:" + port).build())
                .requestTimeout(java.time.Duration.ofSeconds(10))
                .build()) {
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
}
