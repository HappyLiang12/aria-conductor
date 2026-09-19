package io.aria.conductor.execution.sandbox;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.ExecutionLogs;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.ExecutionResult;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.OutputMessage;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunCommandRequest;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxEndpoint;
import io.aria.conductor.execution.adk.TaskExecutionException;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared OpenSandbox lifecycle (frozen contract C0.5) for provider adapters.
 *
 * <p>Owns the SDK connection and the registry of live sandboxes
 * ({@code ownerKey → Sandbox}, where the owner key is an agent id) and provides the
 * provider-neutral operations: create with transient-start retry, workspace upload,
 * endpoint resolution, TTL renewal, kill, blocking / background command launch and a
 * server-level health probe.
 *
 * <p>Provider-specific startup (e.g. {@code opencode serve}), configuration generation
 * and diagnostics stay in the adapters, which consume this class.
 *
 * <p>Frozen behavior (pinned by {@code SandboxLifecycleTest} and the opencode manager
 * tests), verified against OpenSandbox 1.0.18:
 * <ul>
 *   <li>30-minute sandbox TTL; {@code skipHealthCheck=true} at creation because the
 *       server reports execd endpoints without a scheme.</li>
 *   <li>3 create attempts with 2s / 4s exponential backoff on transient start errors
 *       ({@code DOCKER::SANDBOX_START_FAILED}, excluded port range); other failures
 *       fail fast.</li>
 *   <li>Workspace upload walks the tree up to depth 3, skips binary files and files
 *       larger than 4 MiB, and writes text entries under {@code /workspace} with
 *       mode 644.</li>
 *   <li>{@code getSandboxUrl} completes scheme-less endpoints with {@code http://}.</li>
 *   <li>Command execution is {@code sandbox.commands().run(cmd)} — blocking until the
 *       command exits, so long-running processes must be launched via
 *       {@link #runBackgroundCommand(String, String, Map)}, which uses a virtual thread.</li>
 * </ul>
 */
@Slf4j
public class SandboxLifecycle {

    /** Sandbox default TTL. */
    private static final Duration SANDBOX_TIMEOUT = Duration.ofMinutes(30);
    /** Timeout for the server-level health probe. */
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(3);
    /** Max recursion depth when uploading a workspace. */
    private static final int MAX_UPLOAD_DEPTH = 3;
    /** Cap on a single uploaded file to keep requests sane. */
    private static final long MAX_UPLOAD_BYTES = 4 * 1024 * 1024;
    /** Max attempts to create a sandbox before giving up on transient start errors. */
    private static final int MAX_SANDBOX_CREATE_ATTEMPTS = 3;
    /** Base backoff (ms) between sandbox creation retries; doubles each attempt (2s, then 4s). */
    private static final long SANDBOX_CREATE_BACKOFF_BASE_MS = 2000L;

    private final ConnectionConfig connectionConfig;
    /** Raw server base URL (used for the service-level health probe). */
    private final String serverUrl;
    /** ownerKey → live sandbox instance (single table, sandbox id is resolved by walking values). */
    private final Map<UUID, Sandbox> sandboxes = new ConcurrentHashMap<>();

    public SandboxLifecycle(String serverUrl, String apiKey) {
        this.serverUrl = serverUrl != null && !serverUrl.isBlank() ? serverUrl : "http://localhost:8080";
        this.connectionConfig = buildConnectionConfig(serverUrl, apiKey);
    }

    /**
     * Create a sandbox from the given image, optionally injecting env vars
     * (e.g. LLM model credentials for the runtime inside the sandbox).
     *
     * @param ownerKey owner the sandbox is registered under (agent id)
     * @param env env vars to inject into the sandbox; {@code null} or empty is
     *            treated as "no env" and the SDK env call is skipped
     * @return the created sandbox id
     * @throws TaskExecutionException {@code SANDBOX_UNAVAILABLE} if creation fails
     */
    public String createSandbox(UUID ownerKey, String image, Map<String, String> env) {
        Sandbox sandbox = createSandboxWithRetry(ownerKey, image, env);
        String sandboxId = sandbox.getId();
        sandboxes.put(ownerKey, sandbox);
        log.info("OpenSandbox sandbox created for owner {}: {}", ownerKey, sandboxId);
        return sandboxId;
    }

    /**
     * Upload the agent workspace (opencode.json / AGENTS.md etc.) into the sandbox
     * at the workspace root.
     *
     * <p>Files are uploaded one entry at a time via the SDK files API. Text files
     * (UTF-8, under {@link #MAX_UPLOAD_BYTES}) are uploaded; binary files are skipped
     * with a warning since the SDK entry API is text-oriented.
     */
    public void uploadWorkspace(UUID ownerKey, Path workspaceDir) {
        Sandbox sandbox = requireSandboxForAgent(ownerKey);
        if (workspaceDir == null || !Files.isDirectory(workspaceDir)) {
            log.info("Workspace {} for agent {} is empty — nothing to upload", workspaceDir, ownerKey);
            return;
        }
        List<WriteEntry> entries = new ArrayList<>();
        collectEntries(workspaceDir, workspaceDir, 0, entries);
        if (entries.isEmpty()) {
            log.info("No files to upload for agent {} workspace {}", ownerKey, workspaceDir);
            return;
        }
        try {
            sandbox.files().write(entries);
            log.info("Uploaded {} file(s) from workspace {} into sandbox for agent {}",
                    entries.size(), workspaceDir, ownerKey);
        } catch (Exception e) {
            log.error("Workspace upload failed for agent {}: {}", ownerKey, e.getMessage());
            throw new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                    "Workspace upload failed for agent " + ownerKey + ": " + e.getMessage(), e);
        }
    }

    /**
     * Resolve the externally reachable URL for a sandbox-internal port.
     *
     * <p>The server returns scheme-less direct endpoints like
     * {@code 127.0.0.1:{mapped}/proxy/{port}} (execd built-in forwarding on the Docker
     * host, see docker-compose {@code [docker] host_ip}); the scheme is completed here,
     * producing e.g. {@code http://127.0.0.1:40369/proxy/4096}.
     */
    public String getSandboxUrl(String sandboxId, int port) {
        Sandbox sandbox = requireSandbox(sandboxId);
        try {
            SandboxEndpoint endpoint = sandbox.getEndpoint(port);
            String raw = endpoint.getEndpoint();
            return raw.contains("://") ? raw : "http://" + raw;
        } catch (Exception e) {
            throw new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                    "Could not resolve endpoint for sandbox " + sandboxId + " port " + port + ": " + e.getMessage(), e);
        }
    }

    /** Kill a sandbox (idempotent). */
    public void killSandbox(String sandboxId) {
        if (sandboxId == null) {
            return;
        }
        Sandbox sandbox = requireSandbox(sandboxId);
        sandboxes.values().removeIf(sb -> sandboxId.equals(sb.getId()));
        try {
            sandbox.kill();
            log.info("Sandbox {} killed", sandboxId);
        } catch (Exception e) {
            log.warn("Failed to kill sandbox {}: {}", sandboxId, e.getMessage());
        }
    }

    /**
     * Renew the sandbox TTL by the given extension (R3-F2).
     *
     * <p>The OpenSandbox SDK's own heartbeat fires ~9s too late at the 30-minute
     * TTL boundary, so a long-lived synchronous task must renew proactively via
     * {@link Sandbox#renew(Duration)}.
     *
     * @param sandboxId sandbox to renew
     * @param extension TTL extension to request
     * @throws TaskExecutionException {@code SANDBOX_UNAVAILABLE} if the sandbox
     *         is unknown or the renewal call fails
     */
    public void renewSandbox(String sandboxId, Duration extension) {
        Sandbox sandbox = requireSandbox(sandboxId);
        try {
            var resp = sandbox.renew(extension);
            log.info("Sandbox {} renewed until {}", sandboxId, resp.getExpiresAt());
        } catch (Exception e) {
            log.warn("Failed to renew sandbox {}: {}", sandboxId, e.getMessage());
            throw new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                    "Sandbox renewal failed for " + sandboxId + ": " + e.getMessage(), e);
        }
    }

    /**
     * Run a shell command inside the sandbox and return the combined stdout +
     * result text. Blocking until the command exits — callers must not launch
     * long-lived processes here (see {@link #runBackgroundCommand(String, String, Map)}).
     *
     * @param sandboxId sandbox to execute the command in
     * @param command   shell command to run
     * @return the command's combined stdout/result text
     * @throws TaskExecutionException {@code SANDBOX_UNAVAILABLE} if the sandbox is
     *         unknown or the command execution fails
     */
    public String runCommand(String sandboxId, String command) {
        Sandbox sandbox = requireSandbox(sandboxId);
        try {
            var exec = sandbox.commands().run(command);
            return renderExecution(exec);
        } catch (Exception e) {
            throw new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                    "Command execution failed in sandbox " + sandboxId + ": " + e.getMessage(), e);
        }
    }

    /**
     * Launch a long-running command inside the sandbox on a background virtual thread.
     *
     * <p>OpenSandbox command execution blocks until the process exits, so servers
     * (e.g. {@code opencode serve}) must be launched this way; readiness is probed
     * separately by the caller. With a non-empty {@code env} map the command runs via
     * {@code RunCommandRequest.envs} (per-process env vars, the only way to deliver
     * env vars after sandbox creation); an empty/null map uses the plain
     * {@code commands().run(command)} form.
     *
     * <p>A failure inside the launched process is logged, not propagated (the caller
     * has already returned).
     *
     * @param sandboxId sandbox to execute the command in
     * @param command   shell command to run
     * @param env       per-process env vars; {@code null} or empty is treated as "no env"
     * @throws TaskExecutionException {@code SANDBOX_UNAVAILABLE} if the sandbox is unknown
     */
    public void runBackgroundCommand(String sandboxId, String command, Map<String, String> env) {
        Sandbox sandbox = requireSandbox(sandboxId);
        Thread.ofVirtual().start(() -> {
            try {
                if (env == null || env.isEmpty()) {
                    sandbox.commands().run(command);
                } else {
                    sandbox.commands().run(RunCommandRequest.builder()
                            .command(command)
                            .envs(env)
                            .build());
                }
            } catch (Exception e) {
                log.warn("Background command exited in sandbox {}: {}", sandboxId, e.getMessage());
            }
        });
    }

    /**
     * Service-level health probe for the OpenSandbox server itself:
     * {@code GET {serverUrl}/health}. No sandbox / agent context needed.
     *
     * @return {@code true} when the server responds 2xx; {@code false} on any
     *         connectivity or HTTP error (never throws)
     */
    public boolean isServerHealthy() {
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(HEALTH_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1)
                .build()) {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(serverUrl + "/health"))
                    .timeout(HEALTH_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() / 100 == 2;
        } catch (Exception e) {
            log.debug("OpenSandbox server health probe failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * The SDK handle of a tracked sandbox.
     *
     * <p>Escape hatch for provider-specific operations the generic lifecycle does not
     * model (e.g. {@code OpenCodeSandboxManager#diagnose} reading sandbox metrics);
     * providers should use the lifecycle operations for everything else.
     *
     * @throws TaskExecutionException {@code SANDBOX_UNAVAILABLE} if the sandbox is unknown
     */
    public Sandbox sandbox(String sandboxId) {
        return requireSandbox(sandboxId);
    }

    // ---- internal helpers ----

    /**
     * Create a sandbox, retrying transient start/port-bind failures with backoff.
     *
     * <p>The OpenSandbox server intermittently rejects sandbox start when it lands on a
     * Windows excluded port range ({@code DOCKER::SANDBOX_START_FAILED} port-bind error).
     * Such failures are transient, so we retry up to {@link #MAX_SANDBOX_CREATE_ATTEMPTS}
     * attempts with an exponential backoff; any other failure fails fast.
     */
    private Sandbox createSandboxWithRetry(UUID ownerKey, String image, Map<String, String> env) {
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= MAX_SANDBOX_CREATE_ATTEMPTS; attempt++) {
            try {
                return buildSandbox(ownerKey, image, env);
            } catch (Exception e) {
                lastFailure = e;
                boolean transientError = isTransientStartError(e);
                boolean lastAttempt = attempt == MAX_SANDBOX_CREATE_ATTEMPTS;
                if (!transientError || lastAttempt) {
                    log.error("Failed to create OpenSandbox sandbox for agent {}: {}", ownerKey, e.getMessage());
                    throw new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                            "OpenSandbox sandbox creation failed for agent " + ownerKey + ": " + e.getMessage(), e);
                }
                long backoffMs = SANDBOX_CREATE_BACKOFF_BASE_MS << (attempt - 1);
                log.warn("Sandbox creation attempt {}/{} failed for agent {} with transient error '{}'; retrying in {}ms",
                        attempt, MAX_SANDBOX_CREATE_ATTEMPTS, ownerKey, e.getMessage(), backoffMs);
                sleepQuietly(backoffMs);
            }
        }
        throw new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                "OpenSandbox sandbox creation failed for agent " + ownerKey + ": " + lastFailure.getMessage(), lastFailure);
    }

    private Sandbox buildSandbox(UUID ownerKey, String image, Map<String, String> env) {
        Sandbox.Builder builder = Sandbox.builder()
                .connectionConfig(connectionConfig)
                .image(image)
                .timeout(SANDBOX_TIMEOUT)
                // The server reports execd endpoints without a scheme and with the
                // configured host (bridge mode: 127.0.0.1:{mapped}/proxy/{port}, see
                // docker-compose `[docker] host_ip`). We skip the SDK's built-in health
                // check (it would probe the scheme-less endpoint and fail) and instead
                // verify readiness ourselves against the URL built by {@link #getSandboxUrl}.
                .skipHealthCheck(true);
        if (env != null && !env.isEmpty()) {
            builder.env(env);
            log.info("Injecting {} env var(s) into sandbox for agent {}", env.size(), ownerKey);
        }
        return builder.build();
    }

    /**
     * True when the failure looks like a transient sandbox start / port-bind error
     * (e.g. {@code DOCKER::SANDBOX_START_FAILED} or an excluded port range).
     */
    private static boolean isTransientStartError(Exception e) {
        if (e == null || e.getMessage() == null) {
            return false;
        }
        String message = e.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("sandbox_start_failed")
                || message.contains("excluded port")
                || message.contains("port");
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** Best-effort text rendering of a command {@link Execution} (stdout + result text). */
    private String renderExecution(Execution exec) {
        if (exec == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        ExecutionLogs logs = exec.getLogs();
        if (logs != null && logs.getStdout() != null) {
            for (OutputMessage msg : logs.getStdout()) {
                sb.append(msg.getText());
            }
        }
        if (exec.getResult() != null) {
            for (ExecutionResult result : exec.getResult()) {
                sb.append(result.getText());
            }
        }
        return sb.toString();
    }

    private Sandbox requireSandbox(String sandboxId) {
        for (Sandbox sandbox : sandboxes.values()) {
            if (sandboxId.equals(sandbox.getId())) {
                return sandbox;
            }
        }
        throw new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                "Sandbox " + sandboxId + " is not tracked by this manager");
    }

    /** Locate the sandbox owned by an agent. */
    private Sandbox requireSandboxForAgent(UUID ownerKey) {
        Sandbox sandbox = sandboxes.get(ownerKey);
        if (sandbox == null) {
            throw new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                    "No tracked sandbox found for agent " + ownerKey);
        }
        return sandbox;
    }

    private void collectEntries(Path root, Path dir, int depth, List<WriteEntry> out) {
        if (depth > MAX_UPLOAD_DEPTH) {
            return;
        }
        try (var stream = Files.list(dir)) {
            for (Path entry : stream.sorted().toList()) {
                if (Files.isDirectory(entry)) {
                    collectEntries(root, entry, depth + 1, out);
                } else if (Files.isRegularFile(entry)) {
                    try {
                        if (Files.size(entry) > MAX_UPLOAD_BYTES) {
                            log.warn("Skipping oversized workspace file: {}", entry);
                            continue;
                        }
                        byte[] bytes = Files.readAllBytes(entry);
                        if (containsBinary(bytes)) {
                            log.warn("Skipping binary workspace file (SDK upload is text-oriented): {}", entry);
                            continue;
                        }
                        String rel = root.relativize(entry).toString().replace('\\', '/');
                        out.add(WriteEntry.builder()
                                .path("/workspace/" + rel)
                                .data(new String(bytes, StandardCharsets.UTF_8))
                                .mode(644)
                                .build());
                    } catch (IOException e) {
                        log.warn("Could not read workspace file {}, skipping: {}", entry, e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Could not list workspace directory {}: {}", dir, e.getMessage());
        }
    }

    private static boolean containsBinary(byte[] bytes) {
        int check = Math.min(bytes.length, 512);
        for (int i = 0; i < check; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private static ConnectionConfig buildConnectionConfig(String serverUrl, String apiKey) {
        URI uri = URI.create(serverUrl == null ? "http://localhost:8080" : serverUrl);
        String protocol = uri.getScheme() != null ? uri.getScheme() : "http";
        String domain = uri.getAuthority() != null ? uri.getAuthority() : uri.getHost();
        ConnectionConfig.Builder builder = ConnectionConfig.builder()
                .protocol(protocol)
                .domain(domain);
        // The SDK rejects blank keys ("API key cannot be blank"); a null key falls back
        // to the OPEN_SANDBOX_API_KEY env var, which matches the "optional key" contract.
        if (apiKey != null && !apiKey.isBlank()) {
            builder.apiKey(apiKey);
        }
        // keep useServerProxy=false (default): the server-side proxy path
        // (/v1/sandboxes/{id}/proxy/{port}) resolves the target as the sandbox container IP,
        // which is unreachable from this server's Docker network when sandboxes run on the
        // default bridge. Direct endpoints ({@code <host_ip>:{mapped}/proxy/<port>}, execd
        // built-in forwarding) are reachable from this host — docker-compose sets
        // `[docker] host_ip = "127.0.0.1"` so the returned endpoints resolve locally.
        return builder.build();
    }
}
