package io.aria.conductor.execution.adk.opencode;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxEndpoint;
import io.aria.conductor.execution.adk.TaskExecutionException;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
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
    /** Max recursion depth when uploading a workspace. */
    private static final int MAX_UPLOAD_DEPTH = 3;
    /** Cap on a single uploaded file to keep requests sane. */
    private static final long MAX_UPLOAD_BYTES = 4 * 1024 * 1024;

    private final ConnectionConfig connectionConfig;
    /** agentId → live sandbox instance (single table, sandbox id is resolved by walking values). */
    private final Map<UUID, Sandbox> sandboxes = new ConcurrentHashMap<>();

    public OpenCodeSandboxManager(String serverUrl, String apiKey) {
        this.connectionConfig = buildConnectionConfig(serverUrl, apiKey);
    }

    /**
     * Create a sandbox from the given image (blocks until the sandbox is ready).
     *
     * @return the created sandbox id
     * @throws TaskExecutionException {@code SANDBOX_UNAVAILABLE} if creation fails
     */
    public String createSandbox(UUID agentId, String image) {
        try {
            Sandbox sandbox = Sandbox.builder()
                    .connectionConfig(connectionConfig)
                    .image(image)
                    .timeout(SANDBOX_TIMEOUT)
                    .build();
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
     */
    public String getSandboxUrl(String sandboxId, int port) {
        Sandbox sandbox = requireSandbox(sandboxId);
        try {
            SandboxEndpoint endpoint = sandbox.getEndpoint(port);
            return endpoint.getEndpoint();
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
        return builder.build();
    }
}
