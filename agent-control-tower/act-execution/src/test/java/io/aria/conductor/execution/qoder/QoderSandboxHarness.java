package io.aria.conductor.execution.qoder;

import io.aria.conductor.execution.adk.opencode.OpenCodeSandboxManager;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/**
 * Minimal test-side driver for the Qoder Slice A e2e tests (A2-A5): boot a real
 * OpenSandbox sandbox from the pinned image, upload a staging directory into
 * {@code /workspace}, run blocking commands and always kill the sandbox.
 *
 * <p>This is a thin wrapper over the established in-sandbox driver
 * {@link OpenCodeSandboxManager} (the only allow-listed sandbox API of this
 * repository); it deliberately re-implements nothing. Two A1 findings are baked in:
 * <ul>
 *   <li>{@code createSandbox} skips the SDK health check, so the exec channel can
 *       still refuse connections for a few seconds after creation — {@link #boot}
 *       probes it with a trivial command (mirror of the production
 *       {@code OpenCodeAdkProvider#awaitExecdReady}).</li>
 *   <li>A failed boot must not leak a sandbox container: the sandbox is killed
 *       before the failure is rethrown.</li>
 * </ul>
 *
 * <p>{@link #upload} maps the staging root to {@code /workspace} through
 * {@code OpenCodeSandboxManager#uploadWorkspace}: text files only, depth &le; 3,
 * &le; 4 MiB per file, mode 644 (a shell script is therefore run as
 * {@code bash /workspace/<script>.sh}, not executed directly).
 *
 * <p>No credentials are involved anywhere in this flow.
 */
final class QoderSandboxHarness implements AutoCloseable {

    /** Execd-readiness probe interval (mirrors {@code OpenCodeAdkProvider#awaitExecdReady}). */
    private static final Duration EXEC_READY_POLL_INTERVAL = Duration.ofMillis(500);

    /** Execd-readiness budget — a cold first boot of a sandbox image needs several seconds. */
    private static final Duration EXEC_READY_TIMEOUT = Duration.ofSeconds(90);

    private final OpenCodeSandboxManager manager;
    private final UUID agentId;
    private final String sandboxId;

    private QoderSandboxHarness(OpenCodeSandboxManager manager, UUID agentId, String sandboxId) {
        this.manager = manager;
        this.agentId = agentId;
        this.sandboxId = sandboxId;
    }

    /**
     * Create a confirmed-ready sandbox from {@code image} on the OpenSandbox server at
     * {@code serverUrl}. Fails fast (without leaving a sandbox behind) when the server is
     * unhealthy or the exec channel never becomes ready.
     */
    static QoderSandboxHarness boot(String serverUrl, String image) {
        OpenCodeSandboxManager manager = new OpenCodeSandboxManager(serverUrl, null);
        if (!manager.isServerHealthy()) {
            throw new IllegalStateException("OpenSandbox server is not reachable at " + serverUrl
                    + " (`podman compose up -d opensandbox-server` from the repository root)");
        }
        UUID agentId = UUID.randomUUID();
        String sandboxId = null;
        try {
            sandboxId = manager.createSandbox(agentId, image);
            awaitExecdReady(manager, sandboxId);
            return new QoderSandboxHarness(manager, agentId, sandboxId);
        } catch (RuntimeException e) {
            manager.killSandbox(sandboxId);
            throw e;
        }
    }

    /** Upload a staging directory into the sandbox's {@code /workspace} (see class javadoc). */
    void upload(Path stagingDir) {
        manager.uploadWorkspace(agentId, stagingDir);
    }

    /** Run a shell command in the sandbox and return its combined stdout/result text (blocking). */
    String run(String command) {
        return manager.runCommand(sandboxId, command);
    }

    /** The OpenSandbox sandbox id (evidence / log correlation). */
    String sandboxId() {
        return sandboxId;
    }

    /** The synthetic agent id the sandbox is registered under in the driver. */
    UUID agentId() {
        return agentId;
    }

    /** Kill the sandbox (idempotent). */
    @Override
    public void close() {
        manager.killSandbox(sandboxId);
    }

    /**
     * Wait until the sandbox exec service accepts commands.
     *
     * <p>{@code createSandbox} skips the SDK health check (the server reports a scheme-less
     * endpoint), so the exec channel can still refuse connections for a few seconds after the
     * sandbox is reported as created. Mirrors the production gate
     * ({@code OpenCodeAdkProvider#awaitExecdReady}) with a trivial {@code true} command.
     */
    private static void awaitExecdReady(OpenCodeSandboxManager manager, String sandboxId) {
        long deadline = System.currentTimeMillis() + EXEC_READY_TIMEOUT.toMillis();
        Exception lastFailure = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                manager.runCommand(sandboxId, "true");
                return;
            } catch (Exception e) {
                lastFailure = e;
                try {
                    Thread.sleep(EXEC_READY_POLL_INTERVAL.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for sandbox execd readiness", ie);
                }
            }
        }
        throw new IllegalStateException(
                "Sandbox " + sandboxId + " execd did not become ready within " + EXEC_READY_TIMEOUT,
                lastFailure);
    }
}
