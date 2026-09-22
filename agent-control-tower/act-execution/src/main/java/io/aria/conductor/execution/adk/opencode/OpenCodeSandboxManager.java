package io.aria.conductor.execution.adk.opencode;

import io.aria.conductor.execution.adk.TaskExecutionException;
import io.aria.conductor.execution.runtime.sandbox.SandboxLifecycle;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wrapper around the OpenSandbox Java SDK (com.alibaba.opensandbox:sandbox).
 *
 * <p>Handles sandbox lifecycle (create / kill), workspace upload and the
 * {@code opencode serve} bootstrap inside the sandbox. Sandbox-scoped
 * operations are addressed by sandbox id.
 *
 * <p>All SDK access has moved behind the shared run-owned
 * {@link SandboxLifecycle.OpenSandboxSdk}: this class is now the legacy
 * per-agent facade over that one SDK path (its per-agent sandbox registry and
 * the OpenCode-specific {@code serve} command shape live here, not in the
 * lifecycle). The endpoint, upload, renewal and kill semantics it exposes are
 * byte-for-byte the previously verified ones; the run-owned Sandbox backend
 * ({@code io.aria.conductor.execution.runtime.sandbox}) is keyed by run UUID and
 * does not share this agent-keyed registry.
 *
 * <p>Env vars (e.g. LLM model credentials) can be injected into every sandbox via
 * {@link #createSandbox(UUID, String, java.util.Map)}; the SDK builder supports
 * {@code Sandbox.Builder#env(Map)} (verified against OpenSandbox 1.0.18).
 */
@Slf4j
public class OpenCodeSandboxManager {

    private final SandboxLifecycle.OpenSandboxSdk sdk;
    /** agentId → sandbox id of the live sandbox instance. */
    private final Map<UUID, String> sandboxes = new ConcurrentHashMap<>();

    public OpenCodeSandboxManager(String serverUrl, String apiKey) {
        this.sdk = new SandboxLifecycle.OpenSandboxSdk(serverUrl, apiKey);
    }

    /**
     * Create a sandbox from the given image (blocks until the sandbox is ready).
     *
     * @return the created sandbox id
     * @throws TaskExecutionException {@code SANDBOX_UNAVAILABLE} if creation fails
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
     * @throws TaskExecutionException {@code SANDBOX_UNAVAILABLE} if creation fails
     */
    public String createSandbox(UUID agentId, String image, Map<String, String> env) {
        String sandboxId = sdk.create(image, env == null ? Map.of() : env);
        sandboxes.put(agentId, sandboxId);
        log.info("OpenSandbox sandbox created for agent {}: {}", agentId, sandboxId);
        return sandboxId;
    }

    /**
     * Upload the agent workspace (opencode.json / AGENTS.md etc.) into the sandbox
     * at the workspace root.
     *
     * <p>Files are uploaded one entry at a time via the SDK files API. Text files
     * (UTF-8, under the SDK adapter's per-file cap) are uploaded; binary files are
     * skipped with a warning since the SDK entry API is text-oriented.
     */
    public void uploadWorkspace(UUID agentId, Path workspaceDir) {
        sdk.uploadWorkspace(requireSandboxForAgent(agentId), workspaceDir);
    }

    /**
     * Start {@code opencode serve} inside the sandbox on the given port.
     *
     * <p>The serve process is long-running, so the command is launched on a
     * background virtual thread by the SDK adapter.
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
     * is fixed at creation time and the probe-then-write flow decides the token
     * only once the sandbox exists.
     */
    public void runServeCommand(String sandboxId, int port, Map<String, String> env) {
        String command = "opencode serve --hostname 0.0.0.0 --port " + port;
        sdk.runBackground(sandboxId, command, env);
    }

    /**
     * Resolve the externally reachable URL for a sandbox-internal port.
     *
     * <p>The server returns scheme-less direct endpoints like
     * {@code 127.0.0.1:{mapped}/proxy/{port}} (execd built-in forwarding on the
     * Docker host); the scheme is completed here, producing e.g.
     * {@code http://127.0.0.1:40369/proxy/4096}.
     */
    public String getSandboxUrl(String sandboxId, int port) {
        String raw = sdk.endpoint(sandboxId, port);
        return raw == null ? null : (raw.contains("://") ? raw : "http://" + raw);
    }

    /** Kill a sandbox (idempotent). */
    public void killSandbox(String sandboxId) {
        if (sandboxId == null) {
            return;
        }
        if (!sdk.tracks(sandboxId)) {
            throw new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                    "Sandbox " + sandboxId + " is not tracked by this manager");
        }
        sandboxes.values().removeIf(sandboxId::equals);
        sdk.kill(sandboxId);
    }

    /**
     * Renew the sandbox TTL by the given extension (R3-F2).
     *
     * <p>The OpenSandbox SDK's own heartbeat fires ~9s too late at the 30-minute
     * TTL boundary, so a long-lived synchronous task must renew proactively.
     *
     * @throws TaskExecutionException {@code SANDBOX_UNAVAILABLE} if the sandbox
     *         is unknown or the renewal call fails
     */
    public void renewSandbox(String sandboxId, Duration extension) {
        sdk.renew(sandboxId, extension);
    }

    /**
     * Aggregate a diagnostic snapshot of a sandbox: metrics (CPU/memory), the
     * process table, and the recent opencode serve log tail. Never throws — each
     * section is collected independently and failures are recorded as ERROR markers.
     */
    public String diagnose(String sandboxId) {
        return sdk.diagnose(sandboxId);
    }

    /**
     * Run a shell command inside the sandbox and return the combined stdout +
     * result text. Blocking until the command exits — callers must not launch
     * long-lived processes here.
     *
     * @throws TaskExecutionException {@code SANDBOX_UNAVAILABLE} if the sandbox is
     *         unknown or the command execution fails
     */
    public String runCommand(String sandboxId, String command) {
        return sdk.runCommand(sandboxId, command);
    }

    /**
     * Service-level health probe for the OpenSandbox server itself:
     * {@code GET {serverUrl}/health}. No sandbox / agent context needed.
     *
     * @return {@code true} when the server responds 2xx; {@code false} on any
     *         connectivity or HTTP error (never throws)
     */
    public boolean isServerHealthy() {
        return sdk.isServerHealthy();
    }

    /** Locate the sandbox id owned by an agent. */
    private String requireSandboxForAgent(UUID agentId) {
        String sandboxId = sandboxes.get(agentId);
        if (sandboxId == null) {
            throw new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                    "No tracked sandbox found for agent " + agentId);
        }
        return sandboxId;
    }
}
