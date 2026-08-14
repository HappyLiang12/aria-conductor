package io.aria.conductor.execution.adk.opencode;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wrapper around the OpenSandbox Java SDK (com.alibaba.opensandbox:sandbox).
 *
 * <p>Handles sandbox lifecycle (create / kill), workspace upload and the
 * {@code opencode serve} bootstrap inside the sandbox. Sandbox-scoped
 * operations are addressed by sandbox id; the underlying {@link Sandbox}
 * instances are kept in an internal registry.
 *
 * <p>Env vars (e.g. LLM model credentials) can be injected into every sandbox via
 * {@link #createSandbox(UUID, String, java.util.Map)}; the SDK builder supports
 * {@code Sandbox.Builder#env(Map)} (verified against OpenSandbox 1.0.18).
 *
 * <p>SDK notes (verified against OpenSandbox 1.0.18):
 * <ul>
 *   <li>Command execution is {@code sandbox.commands().run(cmd)} — blocking until the
 *       command exits, so long-running processes must be launched on a background thread.</li>
 *   <li>File upload is entry-based ({@link WriteEntry}, text data) — there is no bulk
 *       directory upload API, so workspaces are walked and written entry by entry.</li>
 *   <li>Port exposure uses {@code sandbox.getEndpoint(port)}, which returns the externally
 *       reachable URL for the sandbox-internal port.</li>
 * </ul>
 */
@Slf4j
public class OpenCodeSandboxManager {

    /** Sandbox default TTL. */
    private static final Duration SANDBOX_TIMEOUT = Duration.ofMinutes(30);
    /** Timeout for the server-level health probe. */
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(3);
    /** Max recursion depth when uploading a workspace. */
    private static final int MAX_UPLOAD_DEPTH = 3;
    /** Cap on a single uploaded file to keep requests sane. */
    private static final long MAX_UPLOAD_BYTES = 4 * 1024 * 1024;

    private final ConnectionConfig connectionConfig;
    /** Raw server base URL (used for the service-level health probe). */
    private final String serverUrl;
    /** agentId → live sandbox instance (single table, sandbox id is resolved by walking values). */
    private final Map<UUID, Sandbox> sandboxes = new ConcurrentHashMap<>();

    public OpenCodeSandboxManager(String serverUrl, String apiKey) {
        this.serverUrl = serverUrl != null && !serverUrl.isBlank() ? serverUrl : "http://localhost:8080";
        this.connectionConfig = buildConnectionConfig(serverUrl, apiKey);
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
        try {
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
                log.info("Injecting {} env var(s) into sandbox for agent {}", env.size(), agentId);
            }
            Sandbox sandbox = builder.build();
            String sandboxId = sandbox.getId();
            sandboxes.put(agentId, sandbox);
            log.info("OpenSandbox sandbox created for agent {}: {}", agentId, sandboxId);
            return sandboxId;
        } catch (Exception e) {
            log.error("Failed to create OpenSandbox sandbox for agent {}: {}", agentId, e.getMessage());
            throw new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                    "OpenSandbox sandbox creation failed for agent " + agentId + ": " + e.getMessage(), e);
        }
    }

    /**
     * Upload the agent workspace (opencode.json / AGENTS.md etc.) into the sandbox
     * at the workspace root.
     *
     * <p>Files are uploaded one entry at a time via the SDK files API. Text files
     * (UTF-8, under {@link #MAX_UPLOAD_BYTES}) are uploaded; binary files are skipped
     * with a warning since the SDK entry API is text-oriented.
     */
    public void uploadWorkspace(UUID agentId, Path workspaceDir) {
        Sandbox sandbox = requireSandboxForAgent(agentId);
        if (workspaceDir == null || !Files.isDirectory(workspaceDir)) {
            log.info("Workspace {} for agent {} is empty — nothing to upload", workspaceDir, agentId);
            return;
        }
        List<WriteEntry> entries = new ArrayList<>();
        collectEntries(workspaceDir, workspaceDir, 0, entries);
        if (entries.isEmpty()) {
            log.info("No files to upload for agent {} workspace {}", agentId, workspaceDir);
            return;
        }
        try {
            sandbox.files().write(entries);
            log.info("Uploaded {} file(s) from workspace {} into sandbox for agent {}",
                    entries.size(), workspaceDir, agentId);
        } catch (Exception e) {
            log.error("Workspace upload failed for agent {}: {}", agentId, e.getMessage());
            throw new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                    "Workspace upload failed for agent " + agentId + ": " + e.getMessage(), e);
        }
    }

    /**
     * Start {@code opencode serve} inside the sandbox on the given port.
     *
     * <p>The serve process is long-running, so the command is launched on a background
     * virtual thread; readiness is probed separately via the health endpoint.
     */
    public void runServeCommand(String sandboxId, int port) {
        Sandbox sandbox = requireSandbox(sandboxId);
        String command = "opencode serve --hostname 0.0.0.0 --port " + port;
        log.info("Starting opencode serve in sandbox {}: {}", sandboxId, command);
        Thread.ofVirtual().start(() -> {
            try {
                sandbox.commands().run(command);
            } catch (Exception e) {
                log.warn("opencode serve exited in sandbox {}: {}", sandboxId, e.getMessage());
            }
        });
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

    // ---- internal helpers ----

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
    private Sandbox requireSandboxForAgent(UUID agentId) {
        Sandbox sandbox = sandboxes.get(agentId);
        if (sandbox == null) {
            throw new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                    "No tracked sandbox found for agent " + agentId);
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
