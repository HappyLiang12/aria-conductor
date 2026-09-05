package io.aria.conductor.execution.mcp;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Backend-embedded MCP endpoint configuration (aria.mcp.*).
 * Consumed by act-execution (opencode.json wiring) and act-mcp (server config).
 */
@Data
@Component
@ConfigurationProperties(prefix = "aria.mcp")
public class McpProperties {

    private boolean enabled = true;

    /** {@code none} (v1 default, auth deferred) or {@code token} (Bearer filter active). */
    private String authMode = "none";

    /** When true, MCP tool error results include full stack traces (external-agent debugging). */
    private boolean debug = false;

    /** Bearer token; only used when auth-mode=token. */
    // never appear in logs via toString (Task 11 filter logs)
    @ToString.Exclude
    private String token = "";

    /** Override for the sandbox-reachable host; blank = auto-resolve (SandboxHostResolver). */
    private String sandboxHostAddress = "";

    /** Backend port the sandbox-side MCP client targets. */
    private int port = 8080;

    public boolean isTokenMode() {
        return "token".equalsIgnoreCase(authMode);
    }
}
