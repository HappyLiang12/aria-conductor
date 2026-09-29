package io.aria.conductor.execution.mcp;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Backend-embedded MCP endpoint configuration (aria.mcp.*).
 * Consumed by act-execution (opencode.json wiring) and act-mcp (server config).
 */
@Data
@Component
@ConfigurationProperties(prefix = "aria.mcp")
public class McpProperties {

    private boolean enabled = true;

    /**
     * {@code none} (v1 default, auth deferred), {@code token} (legacy static Bearer
     * filter), or {@code actor} (run-scoped worker tokens verified through
     * ActorTokenService, with the MCP session bound to the authenticated actor).
     */
    private String authMode = "none";

    /**
     * When true, a failed tool call logs its cause with the full stack server-side.
     * A tool error response itself is always the uniform
     * {@code ok/errorType/message} envelope and never carries the stack.
     */
    private boolean debug = false;

    /** Bearer token; only used when auth-mode=token. */
    // never appear in logs via toString (Task 11 filter logs)
    @ToString.Exclude
    private String token = "";

    /** Override for the sandbox-reachable host; blank = auto-resolve (SandboxHostResolver). */
    private String sandboxHostAddress = "";

    /** Backend port the sandbox-side MCP client targets. */
    private int port = 8080;

    /**
     * Read-only platform MCP tools the Aria assistant's own coordinated runs may
     * call without a per-call operator ask (operator decision 2026-09-29, spec
     * amendment). The list applies to the assistant's own platform asks only:
     * the platform's {@code PLATFORM_MCP} delivery and the live native shape a
     * core reports for a platform MCP call ({@code mcp__aria-conductor__<tool>}).
     * Every other ask — and every other run — keeps the per-call operator
     * approval. Only reviewed read-only tools belong here: a mutating,
     * operator-only or unclassified tool must never be added without an explicit
     * operator decision. An empty list disables the policy.
     */
    private List<String> autoApproveReadTools = new ArrayList<>(List.of(
            "list_agents", "get_agent", "list_approvals", "list_notifications",
            "get_unread_notification_count", "list_scheduled_jobs", "get_latest_conversation",
            "get_conversation_timeline", "get_dashboard_summary", "get_dod_status",
            "list_kanban_items", "list_knowledge", "query_knowledge", "list_llm_providers",
            "get_llm_provider", "list_reports", "list_runs", "list_running_runs", "get_run",
            "list_skills", "get_skill", "list_agent_skills", "list_workflow_templates",
            "get_workflow"));

    /**
     * Sandbox execd-readiness wait budget in ms (probed every 500ms). createSandbox
     * skips the SDK health check, so the MCP host probe first waits out the execd
     * warmup window; the former hardcoded 10 x 500ms (~5s) budget was routinely
     * exceeded on cold first boots (observed 13-18s prepares), silently skipping
     * the mcp block. Fresh installs should keep the 15s default or higher.
     */
    private long execdReadyTimeoutMs = 15_000L;

    public boolean isTokenMode() {
        return "token".equalsIgnoreCase(authMode);
    }
}
