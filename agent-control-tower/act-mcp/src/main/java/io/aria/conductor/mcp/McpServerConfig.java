package io.aria.conductor.mcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Backend-embedded MCP endpoint. The starter's own auto-configuration serves the
 * protocol; this configuration gates it behind aria.mcp.enabled (yml maps
 * spring.ai.mcp.server.enabled to the same placeholder) and hosts future
 * tool-registration helpers. Tools are discovered from @Tool-annotated beans.
 * Transport note (verified from spring-ai-autoconfigure-mcp-server 1.0.9 metadata): this starter serves SSE only (sse-endpoint/sse-message-endpoint; no streamable protocol key) — downstream URL shapes (Task 10 opencode.json, Task 12 E2E client) must target /sse with a streamable-then-SSE fallback.
 */
@Configuration
@ConditionalOnProperty(prefix = "aria.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpServerConfig {
}
