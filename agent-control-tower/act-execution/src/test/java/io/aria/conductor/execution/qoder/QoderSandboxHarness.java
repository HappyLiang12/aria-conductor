package io.aria.conductor.execution.qoder;

import io.aria.conductor.execution.adk.opencode.OpenCodeSandboxManager;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
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
 * <p>Plan Global Constraint: every local E2E Qoder run must use a zero-credit model.
 * {@link #boot} therefore reads {@code QODER_E2E_MODEL} (system property or environment,
 * default {@value #DEFAULT_E2E_MODEL}) and fails closed unless it is one of
 * {@link #ZERO_CREDIT_MODELS} or {@code QODER_E2E_ALLOW_PAID=1} is set explicitly.
 * {@link #e2eModel()} exposes the validated value; probe scripts receive it as
 * {@code QODER_E2E_MODEL} and pin every {@code qodercli} invocation with it.
 *
 * <p>No Qoder credentials are required by {@link #boot(String, String)}: that flow ships
 * no PAT or model key to the sandbox. The sandbox-server connection is built with
 * {@code new OpenCodeSandboxManager(serverUrl, null)}, which lets the OpenSandbox SDK
 * fall back to an {@code OPEN_SANDBOX_API_KEY} environment variable if one is set (see
 * {@code OpenCodeSandboxManager#buildConnectionConfig}); the harness itself neither
 * reads nor prints that variable.
 *
 * <p>{@link #boot(String, String, Map)} forwards a caller-supplied env map to the
 * sandbox container (the manager's sanctioned {@code createSandbox(agentId, image, env)}
 * path). Authenticated gates (A3) use it to inject the Qoder PAT as
 * {@code QODER_PERSONAL_ACCESS_TOKEN} — the environment is the only channel that
 * carries it, never argv, files or logs. The harness treats the values as opaque: it
 * neither reads, logs nor prints them (the manager logs the variable count only), and
 * tests must not print the map either.
 */
final class QoderSandboxHarness implements AutoCloseable {

    /** Zero-credit model used when {@code QODER_E2E_MODEL} is not set (plan Global Constraint). */
    static final String DEFAULT_E2E_MODEL = "efficient";

    /** Models priced at 0.00x Credit per the plan's frozen CLI facts. */
    private static final Set<String> ZERO_CREDIT_MODELS = Set.of("efficient", "lite");

    /** Execd-readiness probe interval (mirrors {@code OpenCodeAdkProvider#awaitExecdReady}). */
    private static final Duration EXEC_READY_POLL_INTERVAL = Duration.ofMillis(500);

    /** Execd-readiness budget — a cold first boot of a sandbox image needs several seconds. */
    private static final Duration EXEC_READY_TIMEOUT = Duration.ofSeconds(90);

    private final OpenCodeSandboxManager manager;
    private final UUID agentId;
    private final String sandboxId;
    private final String e2eModel;

    private QoderSandboxHarness(OpenCodeSandboxManager manager, UUID agentId, String sandboxId, String e2eModel) {
        this.manager = manager;
        this.agentId = agentId;
        this.sandboxId = sandboxId;
        this.e2eModel = e2eModel;
    }

    /**
     * Create a confirmed-ready sandbox from {@code image} on the OpenSandbox server at
     * {@code serverUrl}, without sandbox env injection (A2/A4/A5 flows).
     */
    static QoderSandboxHarness boot(String serverUrl, String image) {
        return boot(serverUrl, image, Map.of());
    }

    /**
     * Create a confirmed-ready sandbox from {@code image} on the OpenSandbox server at
     * {@code serverUrl}, injecting {@code sandboxEnv} into the sandbox container
     * (environment only — see the class javadoc: values are opaque to the harness).
     * Fails closed on a non-zero-credit model pin (see class javadoc)
     * and fails fast (without leaving a sandbox behind) when the server is unhealthy or
     * the exec channel never becomes ready.
     */
    static QoderSandboxHarness boot(String serverUrl, String image, Map<String, String> sandboxEnv) {
        String e2eModel = resolveE2eModel(); // fail closed before any sandbox is created
        OpenCodeSandboxManager manager = new OpenCodeSandboxManager(serverUrl, null);
        if (!manager.isServerHealthy()) {
            throw new IllegalStateException("OpenSandbox server is not reachable at " + serverUrl
                    + " (`podman compose up -d opensandbox-server` from the repository root)");
        }
        UUID agentId = UUID.randomUUID();
        String sandboxId = null;
        try {
            sandboxId = manager.createSandbox(agentId, image, sandboxEnv);
            awaitExecdReady(manager, sandboxId);
            return new QoderSandboxHarness(manager, agentId, sandboxId, e2eModel);
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

    /**
     * The validated zero-credit model pin for this run (see class javadoc). Probe scripts
     * receive it as {@code QODER_E2E_MODEL} and pin every {@code qodercli} invocation with
     * {@code -m <model>}.
     */
    String e2eModel() {
        return e2eModel;
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
     * Resolve the zero-credit model pin, failing closed (plan Global Constraint): any
     * value outside {@link #ZERO_CREDIT_MODELS} is rejected unless
     * {@code QODER_E2E_ALLOW_PAID=1} is set explicitly.
     */
    private static String resolveE2eModel() {
        String model = setting("QODER_E2E_MODEL", DEFAULT_E2E_MODEL);
        boolean allowPaid = "1".equals(setting("QODER_E2E_ALLOW_PAID", ""));
        if (!ZERO_CREDIT_MODELS.contains(model) && !allowPaid) {
            throw new IllegalStateException("QODER_E2E_MODEL='" + model + "' is not a zero-credit model "
                    + ZERO_CREDIT_MODELS + "; local E2E Qoder runs must use a 0.00x-credit model. "
                    + "Set QODER_E2E_ALLOW_PAID=1 to use a paid model explicitly.");
        }
        return model;
    }

    /** Read a setting from the system properties first, then the environment. */
    private static String setting(String key, String defaultValue) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            value = System.getenv(key);
        }
        return value == null || value.isBlank() ? defaultValue : value.trim();
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
