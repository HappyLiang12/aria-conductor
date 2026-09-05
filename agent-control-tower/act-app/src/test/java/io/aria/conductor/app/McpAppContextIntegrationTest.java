package io.aria.conductor.app;

import io.aria.conductor.ActApplication;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-production-wiring guard: boots the REAL application context (datasource,
 * filters, both MCP transports) with aria.mcp ENABLED, then negotiates a
 * streamable MCP session over HTTP and lists tools. Catches config drift
 * (aria.mcp.* / spring.ai.mcp.server.* renames, filter ordering, bean clashes)
 * that the slim act-mcp bootstrap cannot see.
 * <p>
 * Runs in the Failsafe lane: {@code mvn verify -pl act-app} (Surefire excludes
 * {@code *IntegrationTest}; see parent pom test tiering).
 * <p>
 * Datasource note: follows the {@link BaseH2IntegrationTest} convention — the
 * {@code test} profile's own in-memory H2 ({@code jdbc:h2:mem:act_test}) — so it
 * does NOT contend for the {@code ./data/act_db} H2 file lock held by a backend
 * running on :8080; the backend can stay up. The file lock would only matter if
 * this slice were pointed at the {@code h2} file profile.
 */
@SpringBootTest(
        classes = {ActApplication.class, NoopLlmTestConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "aria.mcp.enabled=true",
                "spring.ai.mcp.server.enabled=true"
        })
@ActiveProfiles({"test", "noop-llm"})
class McpAppContextIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    ApplicationContext context;

    @Test
    void realContext_streamableHandshake_listsCuratedTools() {
        assertThat(context.containsBean("ariaToolCallbackProvider")).isTrue();

        try (McpSyncClient client = McpClient.sync(
                        HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                                .endpoint("/mcp")
                                .build())
                .requestTimeout(java.time.Duration.ofSeconds(15))
                .build()) {
            client.initialize();
            McpSchema.ListToolsResult tools = client.listTools();

            assertThat(tools.tools())
                    .extracting(McpSchema.Tool::name)
                    .contains("list_workflow_templates", "instantiate_workflow_template", "get_workflow",
                            "list_knowledge", "list_approvals", "decide_approval");
        }
    }
}
