package io.aria.conductor.mcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Backend-embedded MCP endpoint. The starter's own auto-configuration serves the
 * protocol; this configuration gates it behind aria.mcp.enabled (yml maps
 * spring.ai.mcp.server.enabled to the same placeholder) and hosts future
 * tool-registration helpers. Tools are discovered from @Tool-annotated beans.
 */
@Configuration
@ConditionalOnProperty(prefix = "aria.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpServerConfig {
}
