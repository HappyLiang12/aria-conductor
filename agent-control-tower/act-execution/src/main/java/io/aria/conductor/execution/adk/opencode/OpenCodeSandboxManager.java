package io.aria.conductor.execution.adk.opencode;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.execution.sandbox.SandboxLifecycle;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * OpenCode-specific adapter over the shared {@link SandboxLifecycle}.
 *
 * <p>Delegates the provider-neutral sandbox lifecycle (create / kill, workspace
 * upload, endpoint resolution, renewal, command launch, server health) and keeps
 * only what is opencode-specific: the {@code opencode serve} bootstrap command and
 * the diagnostic snapshot (metrics + process table + opencode log tail).
 *
 * <p>Env vars (e.g. LLM model credentials) can be injected into every sandbox via
 * {@link #createSandbox(UUID, String, java.util.Map)}; the SDK builder supports
 * {@code Sandbox.Builder#env(Map)} (verified against OpenSandbox 1.0.18).
 *
 * <p>SDK notes (verified against OpenSandbox 1.0.18):
 * <ul>
 *   <li>Command execution is {@code sandbox.commands().run(cmd)} — blocking until the
 *       command exits, so long-running processes are launched on a background thread
 *       by {@link SandboxLifecycle#runBackgroundCommand(String, String, Map)}.</li>
 *   <li>File upload is entry-based ({@link com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry},
 *       text data) — there is no bulk directory upload API, so workspaces are walked
 *       and written entry by entry.</li>
 *   <li>Port exposure uses {@code sandbox.getEndpoint(port)}, which returns the externally
 *       reachable URL for the sandbox-internal port.</li>
 * </ul>
 */
@Slf4j
public class OpenCodeSandboxManager {

    /** JSON serializer for the metrics section (shared, thread-safe). */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Shared sandbox lifecycle (owns the SDK connection and the tracked sandboxes). */
    private final SandboxLifecycle lifecycle;

    public OpenCodeSandboxManager(String serverUrl, String apiKey) {
        this.lifecycle = new SandboxLifecycle(serverUrl, apiKey);
    }

    /**
     * Create a sandbox from the given image (blocks until the sandbox is ready).
     *
     * @return the created sandbox id
     * @throws io.aria.conductor.execution.adk.TaskExecutionException
     *         {@code SANDBOX_UNAVAILABLE} if creation fails
     */
    public String createSandbox(UUID agentId, String image) {
        return createSandbox(agentId, image, null);
    }

    /**
     * Create a sandbox from the given image, optionally injecting env vars
     * (e.g. LLM model credentials for opencode inside the sandbox).
     *
     * @param env env vars to inject into the sandbox; {@code null} or empty is
     *            treated as "no env" and the SDK env call is skipped
     * @return the created sandbox id
     * @throws io.aria.conductor.execution.adk.TaskExecutionException
     *         {@code SANDBOX_UNAVAILABLE} if creation fails
     */
    public String createSandbox(UUID agentId, String image, Map<String, String> env) {
        return lifecycle.createSandbox(agentId, image, env);
    }

    /**
     * Upload the agent workspace (opencode.json / AGENTS.md etc.) into the sandbox
     * at the workspace root (text files only, depth ≤ 3, ≤ 4 MiB, mode 644 — see
     * {@link SandboxLifecycle#uploadWorkspace(UUID, Path)}).
     */
    public void uploadWorkspace(UUID agentId, Path workspaceDir) {
        lifecycle.uploadWorkspace(agentId, workspaceDir);
    }

    /**
     * Start {@code opencode serve} inside the sandbox on the given port.
     *
     * <p>The serve process is long-running, so the command is launched on a background
     * virtual thread; readiness is probed separately via the health endpoint.
     */
    public void runServeCommand(String sandboxId, int port) {
        runServeCommand(sandboxId, port, null);
    }

    /**
     * Start {@code opencode serve} with per-process env vars (e.g. the MCP bearer
     * token {@code ARIA_MCP_TOKEN} the sandbox's opencode.json references via
     * {@code {env:ARIA_MCP_TOKEN}}).
     *
     * <p>OpenSandbox execd supports per-command env natively
     * ({@code RunCommandRequest.envs}); this is the only way to deliver env vars
     * AFTER sandbox creation, since container-level env ({@code createSandbox})
     * is fixed at creation time and the probe-then-write flow (spec §3 fallback)
     * decides the token only once the sandbox exists.
     *
     * <p>The serve process is long-running, so the command is launched on a background
     * virtual thread; readiness is probed separately via the health endpoint.
     */
    public void runServeCommand(String sandboxId, int port, Map<String, String> env) {
        String command = "opencode serve --hostname 0.0.0.0 --port " + port;
        log.info("Starting opencode serve in sandbox {}: {}", sandboxId, command);
        lifecycle.runBackgroundCommand(sandboxId, command, env);
    }

    /**
     * Resolve the externally reachable URL for a sandbox-internal port (scheme-less
     * server endpoints are completed with {@code http://}, see
     * {@link SandboxLifecycle#getSandboxUrl(String, int)}).
     */
    public String getSandboxUrl(String sandboxId, int port) {
        return lifecycle.getSandboxUrl(sandboxId, port);
    }

    /** Kill a sandbox (idempotent). */
    public void killSandbox(String sandboxId) {
        lifecycle.killSandbox(sandboxId);
    }

    /**
     * Renew the sandbox TTL by the given extension (R3-F2).
     *
     * <p>The OpenSandbox SDK's own heartbeat fires ~9s too late at the 30-minute
     * TTL boundary, so a long-lived synchronous task must renew proactively.
     *
     * @param sandboxId sandbox to renew
     * @param extension TTL extension to request
     * @throws io.aria.conductor.execution.adk.TaskExecutionException
     *         {@code SANDBOX_UNAVAILABLE} if the sandbox is unknown or the renewal fails
     */
    public void renewSandbox(String sandboxId, Duration extension) {
        lifecycle.renewSandbox(sandboxId, extension);
    }

    /**
     * Aggregate a diagnostic snapshot of a sandbox: metrics (CPU/memory), the
     * process table, and the recent opencode serve log tail. Never throws — each
     * section is collected independently and failures are recorded as ERROR markers.
     *
     * @param sandboxId sandbox to inspect
     * @return a human-readable multi-section diagnostic text
     */
    public String diagnose(String sandboxId) {
        Sandbox sandbox = lifecycle.sandbox(sandboxId);
        StringBuilder sb = new StringBuilder();
        // 1. metrics (CPU/memory) — proves opencode is actually working
        try {
            var metrics = sandbox.getMetrics();
            String metricsText;
            try {
                metricsText = OBJECT_MAPPER.writeValueAsString(metrics);
            } catch (Exception jsonEx) {
                metricsText = String.valueOf(metrics);
            }
            sb.append("== metrics ==\n").append(metricsText).append('\n');
        } catch (Exception e) {
            sb.append("== metrics == ERROR ").append(e.getMessage()).append('\n');
        }
        // 2. process snapshot inside the sandbox
        try {
            String rendered = lifecycle.runCommand(sandboxId, "ps aux 2>/dev/null | head -30 || ps -ef | head -30");
            if (rendered == null || rendered.isBlank()) {
                throw new IllegalStateException("ps returned no output");
            }
            sb.append("== processes ==\n").append(rendered).append('\n');
        } catch (Exception e) {
            // ps unavailable or empty — fall back to a /proc scan
            try {
                sb.append("== processes (proc fallback) ==\n")
                        .append(lifecycle.runCommand(sandboxId, "ls /proc | grep -E '^[0-9]+$' | head -30"))
                        .append('\n');
            } catch (Exception fallbackEx) {
                sb.append("== processes == ERROR ").append(e.getMessage()).append('\n');
            }
        }
        // 3. opencode log tail (best-effort single command, two common log locations)
        try {
            sb.append("== opencode log tail ==\n")
                    .append(lifecycle.runCommand(sandboxId,
                            "tail -50 $(ls -t ~/.opencode/log/*.log 2>/dev/null | head -1) 2>/dev/null"
                            + " || tail -50 $(ls -t ~/.local/share/opencode/log/*.log 2>/dev/null | head -1) 2>/dev/null"
                            + " || echo 'no opencode log found'"))
                    .append('\n');
        } catch (Exception e) {
            sb.append("== opencode log tail == ERROR ").append(e.getMessage()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Run a shell command inside the sandbox and return the combined stdout +
     * result text (same rendering as {@link #diagnose(String)} sections). Blocking
     * until the command exits — callers must not launch long-lived processes here.
     *
     * @param sandboxId sandbox to execute the command in
     * @param command   shell command to run
     * @return the command's combined stdout/result text
     * @throws io.aria.conductor.execution.adk.TaskExecutionException
     *         {@code SANDBOX_UNAVAILABLE} if the sandbox is unknown or the command fails
     */
    public String runCommand(String sandboxId, String command) {
        return lifecycle.runCommand(sandboxId, command);
    }

    /**
     * Service-level health probe for the OpenSandbox server itself:
     * {@code GET {serverUrl}/health}. No sandbox / agent context needed.
     *
     * @return {@code true} when the server responds 2xx; {@code false} on any
     *         connectivity or HTTP error (never throws)
     */
    public boolean isServerHealthy() {
        return lifecycle.isServerHealthy();
    }
}
