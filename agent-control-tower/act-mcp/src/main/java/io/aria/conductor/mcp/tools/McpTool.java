package io.aria.conductor.mcp.tools;

/**
 * Marker for beans whose @Tool methods must be registered with the embedded MCP
 * server. McpServerConfig bridges every McpTool bean into one
 * MethodToolCallbackProvider (Spring AI 1.0.9 has no @Tool-bean auto-discovery).
 */
public interface McpTool {
}
