package io.aria.conductor.mcp;

import io.aria.conductor.mcp.tools.WorkflowTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Backend-embedded MCP endpoint. The starter's own auto-configuration serves the
 * protocol; this configuration gates it behind aria.mcp.enabled (yml maps
 * spring.ai.mcp.server.enabled to the same placeholder) and hosts the
 * tool-registration helper.
 * Transport note (verified from spring-ai-autoconfigure-mcp-server 1.0.9 metadata): this starter serves SSE only (sse-endpoint/sse-message-endpoint; no streamable protocol key) — downstream URL shapes (Task 10 opencode.json, Task 12 E2E client) must target /sse with a streamable-then-SSE fallback.
 */
@Configuration
@ConditionalOnProperty(prefix = "aria.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpServerConfig {

    /**
     * spring-ai 1.0.9's MCP server auto-configuration consumes ToolCallback /
     * ToolCallbackProvider beans only — @Tool-annotated tool beans are NOT
     * auto-discovered. This provider bridges the platform tool beans; extend
     * toolObjects as new tool modules land (Task 7: knowledge + approval tools).
     */
    @Bean
    public ToolCallbackProvider ariaToolCallbackProvider(WorkflowTools workflowTools) {
        return MethodToolCallbackProvider.builder().toolObjects(workflowTools).build();
    }
}
