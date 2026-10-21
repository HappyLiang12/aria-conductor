package io.aria.conductor.execution.qoder;

import io.aria.conductor.execution.runtime.sandbox.SandboxLifecycle;

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
 * <p>This is a thin wrapper over the production sandbox SDK
 * ({@link SandboxLifecycle.OpenSandboxSdk}) the core runtime itself uses; it
 * deliberately re-implements nothing. Two A1 findings are baked in:
 * <ul>
 *   <li>{@code create} skips the SDK health check, so the exec channel can
 *       still refuse connections for a few seconds after creation — {@link #boot}
 *       probes it with a trivial command.</li>
 *   <li>A failed boot must not leak a sandbox container: the sandbox is killed
 *       before the failure is rethrown.</li>
 * </ul>
 *
 * <p>{@link #upload} maps the staging root to {@code /workspace} through the
 * production upload path: text files only, depth &le; 3, &le; 4 MiB per file,
 * mode 644 (a shell script is therefore run as
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
 * no PAT or model key to the sandbox. The SDK connection is built with
 * {@code new SandboxLifecycle.OpenSandboxSdk(serverUrl, null)}, which lets the
 * OpenSandbox SDK fall back to an {@code OPEN_SANDBOX_API_KEY} environment variable
 * if one is set; the harness itself neither reads nor prints that variable.
 *
 * <p>{@link #boot(String, String, Map)} forwards a caller-supplied env map to the
 * sandbox container. Authenticated gates (A3) use it to inject the Qoder PAT as
 * {@code QODER_PERSONAL_ACCESS_TOKEN} — the environment is the only channel that
 * carries it, never argv, files or logs. The harness treats the values as opaque: it
 * neither reads, logs nor prints them, and tests must not print the map either.
 */
final class QoderSandboxHarness implements AutoCloseable {

    /** Zero-credit model used when {@code QODER_E2E_MODEL} is not set (plan Global Constraint). */
    static final String DEFAULT_E2E_MODEL = "efficient";

    /** Models priced at 0.00x Credit per the plan's frozen CLI facts. */
    private static final Set<String> ZERO_CREDIT_MODELS = Set.of("efficient", "lite");

    /** Execd-readiness probe interval. */
    private static final Duration EXEC_READY_POLL_INTERVAL = Duration.ofMillis(500);

    /** Execd-readiness budget — a cold first boot of a sandbox image needs several seconds. */
    private static final Duration EXEC_READY_TIMEOUT = Duration.ofSeconds(90);

    private final SandboxLifecycle.OpenSandboxSdk sdk;
    private final UUID agentId;
    private final String sandboxId;
    private final String e2eModel;

    private QoderSandboxHarness(SandboxLifecycle.OpenSandboxSdk sdk, UUID agentId,
                                String sandboxId, String e2eModel) {
        this.sdk = sdk;
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
        SandboxLifecycle.OpenSandboxSdk sdk = new SandboxLifecycle.OpenSandboxSdk(serverUrl, null);
        if (!sdk.isServerHealthy()) {
            throw new IllegalStateException("OpenSandbox server is not reachable at " + serverUrl
                    + " (`podman compose up -d opensandbox-server` from the repository root)");
        }
        UUID agentId = UUID.randomUUID();
        String sandboxId = null;
        try {
            sandboxId = sdk.create(image, sandboxEnv);
            awaitExecdReady(sdk, sandboxId);
            return new QoderSandboxHarness(sdk, agentId, sandboxId, e2eModel);
        } catch (RuntimeException e) {
            sdk.kill(sandboxId);
            throw e;
        }
    }

    /** Upload a staging directory into the sandbox's {@code /workspace} (see class javadoc). */
    void upload(Path stagingDir) {
        sdk.uploadWorkspace(sandboxId, stagingDir);
    }

    /** Run a shell command in the sandbox and return its combined stdout/result text (blocking). */
    String run(String command) {
        return sdk.runCommand(sandboxId, command);
    }

    /**
     * Renew this sandbox's TTL through the production SDK path, so gates can exercise
     * the same call the runtime uses (A5). Callers that need to observe the effect read
     * it back through the SDK; this passthrough only exists because a second client
     * cannot target this harness's sandbox. Do not copy {@code Sandbox.resumer()} for
     * such reads: it asks the server to resume the sandbox and is rejected with HTTP
     * 409 Conflict while the sandbox is running (observed on every A5 read), so
     * {@code Sandbox.connector()} is the attach path that works.
     */
    void renew(Duration extension) {
        sdk.renew(sandboxId, extension);
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
        sdk.kill(sandboxId);
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
     * <p>{@code create} skips the SDK health check (the server reports a scheme-less
     * endpoint), so the exec channel can still refuse connections for a few seconds
     * after the sandbox is reported as created. Probes with a trivial {@code true}
     * command.
     */
    private static void awaitExecdReady(SandboxLifecycle.OpenSandboxSdk sdk, String sandboxId) {
        long deadline = System.currentTimeMillis() + EXEC_READY_TIMEOUT.toMillis();
        Exception lastFailure = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                sdk.runCommand(sandboxId, "true");
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
