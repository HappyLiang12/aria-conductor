package io.aria.conductor.app.e2e;

import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.execution.runtime.ControlStrategy;
import io.aria.conductor.execution.runtime.CoreAdapter;
import io.aria.conductor.execution.runtime.CoreCapabilities;
import io.aria.conductor.execution.runtime.CoreSession;
import io.aria.conductor.execution.runtime.ExecutionSpec;
import io.aria.conductor.execution.runtime.LaunchProfile;
import io.aria.conductor.execution.runtime.PreparedEnvironment;
import io.aria.conductor.execution.runtime.RuntimeHandle;
import io.aria.conductor.execution.runtime.SecretBundle;
import io.aria.conductor.execution.runtime.core.QoderCoreAdapter;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Harness Qoder core adapter (Task 19 peer-launch wiring): the production
 * {@code qoder} core served by the real committed run-bound ACP bridge
 * ({@code bridge/main.js}) driving the committed mock Qoder CLI
 * ({@code peers/mock-qoder.mjs}) instead of a pinned {@code qodercli} binary.
 *
 * <p>The launch is the bridge invocation the production adapter produces, with
 * the mock peer as the bridge's CLI child and the harness scenario forwarded as
 * a child environment fixture selection (the peer reads
 * {@code ARIA_PEER_SCENARIO}/{@code ARIA_PEER_WORKSPACE} and refuses to start
 * without them). The run's minted bridge control secret is written into the
 * {@code --control-secret-file} the profile names by the harness "trusted
 * launcher" ({@link CoreE2eProcessBackend}) and handed to the session here; the
 * production session against the bridge is unchanged
 * ({@link QoderCoreAdapter#open}).
 *
 * <p>The harness deliberately carries no credential: the mock CLI authenticates
 * nothing, so no {@code --credential-env}/{@code --credential-file} pair is
 * emitted and no runtime credential row is needed for a harness run.
 */
final class CoreE2eQoderAdapter implements CoreAdapter {

    static final String CORE_ID = "qoder";

    /** Fixture model pin: {@code fixtures.models.default} of the committed manifest. */
    static final String FIXTURE_MODEL = "efficient";
    /** Reviewed pinned core version of the committed peer. */
    static final String FIXTURE_VERSION = "1.1.61";

    private final CoreE2eScenarios scenarios;
    private final CoreE2eProcessBackend.State peers;
    private final Path peerScript;
    private final String nodeExecutable;
    private final String coreExecutable;
    private final String bridgeEntry;
    private final QoderCoreAdapter delegate;

    /**
     * The harness Qoder core. {@code nodeExecutable} is the configured process
     * executable the bridge launch names; {@code coreExecutable} is the
     * absolute existing executable the bridge must receive for {@code --cli}
     * (see {@link #resolveAbsoluteCoreExecutable}) -- the two are the same node
     * binary, and they are separate parameters because the bridge contract is
     * about the flag value, not about how the bridge itself is resolved by the
     * OS.
     */
    CoreE2eQoderAdapter(CoreE2eScenarios scenarios, CoreE2eProcessBackend.State peers,
            String nodeExecutable, String coreExecutable, Path bridgeEntry, Path peerScript) {
        this.scenarios = Objects.requireNonNull(scenarios, "scenarios");
        this.peers = Objects.requireNonNull(peers, "peers");
        this.nodeExecutable = Objects.requireNonNull(nodeExecutable, "nodeExecutable");
        this.coreExecutable = requireAbsoluteCoreExecutable(coreExecutable);
        this.peerScript = Objects.requireNonNull(peerScript, "peerScript");
        this.bridgeEntry = Objects.requireNonNull(bridgeEntry, "bridgeEntry").toString();
        // The production adapter is the session/behavior authority; its profile
        // carries exactly the reviewed artifacts of the harness (bridge entry,
        // the mock CLI), never a real credential.
        this.delegate = new QoderCoreAdapter(new QoderCoreAdapter.QoderProfile(
                nodeExecutable, bridgeEntry.toString(), this.coreExecutable,
                List.of(peerScript.toString()), Map.of(), Map.of(), FIXTURE_VERSION, FIXTURE_MODEL));
    }

    /**
     * The bridge-contract guard of the wired executable: the value handed to the
     * bridge for {@code --cli} must be an absolute existing file, the only shape
     * the committed bridge accepts (a bare name is refused with {@code E_CONFIG}
     * before it ever binds its endpoint).
     */
    static String requireAbsoluteCoreExecutable(String coreExecutable) {
        Objects.requireNonNull(coreExecutable, "coreExecutable");
        Path path = Path.of(coreExecutable);
        if (!path.isAbsolute() || !Files.isRegularFile(path)) {
            throw new IllegalStateException("The Qoder bridge launch requires the core executable to be an"
                    + " absolute existing file, but it was: " + coreExecutable);
        }
        return path.normalize().toString();
    }

    /**
     * The mock CLI is a Node script, so the bridge's {@code --cli} names the node
     * executable. The committed bridge accepts only an <em>absolute existing</em>
     * executable there ({@code main.ts}: a bare name is refused with
     * {@code E_CONFIG} before the bridge ever binds its endpoint), and a bridge
     * that exited is indistinguishable from a slow one at the readiness gate --
     * that is exactly how the harness's own launch wire deadlocked the qoder
     * runs. A configured bare name is therefore resolved against the harness
     * process's own {@code PATH} (the same resolution the launcher applies when
     * it spawns {@code node} itself), and a name with no absolute answer on disk
     * refuses the boot loudly instead of launching a bridge the bridge contract
     * must reject.
     */
    static String resolveAbsoluteCoreExecutable(String configured) {
        return resolveAbsoluteCoreExecutable(configured, processPath(), pathExtensions());
    }

    /**
     * The resolution itself, with the harness environment's {@code PATH} and
     * file extensions as explicit inputs (test seam): the configured path when
     * it is already an existing absolute file, else the first {@code PATH}
     * candidate that is one.
     */
    static String resolveAbsoluteCoreExecutable(String configured, String pathEnvironment,
            List<String> extensions) {
        Objects.requireNonNull(configured, "configured");
        Objects.requireNonNull(pathEnvironment, "pathEnvironment");
        Objects.requireNonNull(extensions, "extensions");
        Path configuredPath = Path.of(configured);
        if (configuredPath.isAbsolute()) {
            if (!Files.isRegularFile(configuredPath)) {
                throw new IllegalStateException("The Qoder bridge launch requires an absolute existing core"
                        + " executable, but the configured " + configured + " does not exist");
            }
            return configuredPath.normalize().toString();
        }
        for (Path candidate : pathCandidates(pathEnvironment, configured, extensions)) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        throw new IllegalStateException("The Qoder bridge launch requires an absolute existing core executable,"
                + " and the configured " + configured + " does not resolve to one on PATH=" + pathEnvironment);
    }

    /** The harness process's {@code PATH} (Windows exposes it with unpredictable casing). */
    private static String processPath() {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
                if ("path".equals(entry.getKey().toLowerCase(java.util.Locale.ROOT))) {
                    return entry.getValue();
                }
            }
            return "";
        }
        return path;
    }

    /**
     * The files an unpathed executable name can name: the name itself and, on
     * Windows, the name plus each extension (so {@code node} resolves to
     * {@code node.exe}), tried in every {@code PATH} directory.
     */
    private static List<Path> pathCandidates(String pathEnvironment, String name, List<String> extensions) {
        List<Path> candidates = new ArrayList<>();
        for (String directory : pathEnvironment.split(java.io.File.pathSeparator)) {
            if (directory.isBlank()) {
                continue;
            }
            Path root = Path.of(directory);
            candidates.add(root.resolve(name));
            for (String extension : extensions) {
                candidates.add(root.resolve(name + extension));
            }
        }
        return candidates;
    }

    /** {@code PATHEXT} entries (lower-cased from the usual upper-case form), or the Windows minimal set. */
    private static List<String> pathExtensions() {
        String extensions = System.getenv("PATHEXT");
        if (extensions == null || extensions.isBlank()) {
            extensions = ".EXE;.CMD;.BAT;.COM";
        }
        List<String> resolved = new ArrayList<>();
        for (String extension : extensions.split(";")) {
            String trimmed = extension.trim();
            if (!trimmed.isEmpty()) {
                resolved.add(trimmed.toLowerCase(java.util.Locale.ROOT));
            }
        }
        return resolved;
    }

    @Override
    public String coreId() {
        return CORE_ID;
    }

    /**
     * The harness Qoder core's control surface: the harness serves both
     * placements through its own process transport, where pause/resume are the
     * controller's verified process suspension of the owned tree (the recorded
     * technique) -- the surface the bridge itself cannot offer (it refuses a
     * pause RPC with {@code E_PAUSE_UNSUPPORTED}) and the same surface the
     * harness OpenCode core declares. The production SANDBOX row
     * ({@code UNVERIFIED}) describes a real sandbox placement, which this
     * harness transport never performs.
     */
    @Override
    public CoreCapabilities capabilities(ExecutionMode mode) {
        Objects.requireNonNull(mode, "mode");
        return new CoreCapabilities(ControlStrategy.BACKEND_SUSPEND, true, false, true);
    }

    @Override
    public boolean serviceHealthy() {
        return true;
    }

    @Override
    public LaunchProfile launchProfile(ExecutionSpec spec, PreparedEnvironment environment,
            SecretBundle credentials) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(environment, "environment");
        requireCore(spec);
        String scenario = scenarios.requireScenario(spec.agentId());
        URI endpoint = requireLoopbackEndpoint(environment);
        Path workingDirectory = Path.of(environment.workingDirectory()).toAbsolutePath().normalize();
        Map<String, String> childEnvironment = new LinkedHashMap<>();
        childEnvironment.put(CoreE2eScenarios.PEER_CONTROL_TOKEN_ENV, scenarios.peerControlToken());
        childEnvironment.put("ARIA_PEER_SCENARIO", scenario);
        childEnvironment.put("ARIA_PEER_WORKSPACE", workingDirectory.toString());

        List<String> argv = new ArrayList<>();
        argv.add(nodeExecutable);
        argv.add(bridgeEntry);
        add(argv, "--run-id", spec.runId().toString());
        add(argv, "--workspace", workingDirectory.toString());
        add(argv, "--model", FIXTURE_MODEL);
        argv.add("--cli");
        argv.add(coreExecutable);
        add(argv, "--cli-arg", peerScript.toString());
        for (Map.Entry<String, String> entry : new TreeMap<>(childEnvironment).entrySet()) {
            add(argv, "--child-env", entry.getKey() + "=" + entry.getValue());
        }
        add(argv, "--control-secret-file", QoderCoreAdapter.controlSecretFile(environment).toString());
        add(argv, "--host", endpoint.getHost());
        add(argv, "--port", String.valueOf(endpoint.getPort()));
        return new LaunchProfile(argv, Map.of(), workingDirectory.toString());
    }

    /**
     * Opens the production bridge session. The control secret the bridge reads
     * from its {@code --control-secret-file} is the one the harness launcher
     * minted for this run, so it is supplied here under the name the production
     * adapter requires ({@value QoderCoreAdapter#CONTROL_SECRET_ENVIRONMENT}).
     */
    @Override
    public CoreSession open(RuntimeHandle handle, ExecutionSpec spec, SecretBundle credentials) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(credentials, "credentials");
        requireCore(spec);
        String controlSecret = peers.controlSecret(spec.runId());
        if (controlSecret == null || controlSecret.isBlank()) {
            throw new IllegalStateException("Run " + spec.runId()
                    + " carries no harness control secret; the bridge session cannot be authenticated");
        }
        // Launch returns as soon as the bridge process is owned; the bridge has
        // not necessarily bound its port yet. Wait for the authenticated session
        // binding -- exactly the production host backend's launch gate -- before
        // opening the session, so the session open can never race the bind.
        CoreE2eEndpointReadiness.awaitQoderBridge(handle.endpoint(), controlSecret, spec.runId());
        Map<String, String> environment = new LinkedHashMap<>(credentials.environment());
        environment.put(QoderCoreAdapter.CONTROL_SECRET_ENVIRONMENT, controlSecret);
        return delegate.open(handle, spec, new SecretBundle(credentials.reference(), environment));
    }

    private void requireCore(ExecutionSpec spec) {
        if (!CORE_ID.equals(spec.coreId())) {
            throw new IllegalArgumentException("The harness Qoder adapter does not serve core " + spec.coreId());
        }
    }

    private static URI requireLoopbackEndpoint(PreparedEnvironment environment) {
        URI endpoint = Objects.requireNonNull(environment.endpoint(),
                "The harness Qoder launch requires the prepared protocol endpoint");
        String host = endpoint.getHost();
        boolean loopback = "127.0.0.1".equals(host) || "::1".equals(host);
        if (!loopback || endpoint.getPort() < 1) {
            throw new IllegalStateException("The harness Qoder bridge endpoint must be a loopback host:port, got: "
                    + endpoint);
        }
        return endpoint;
    }

    private static void add(List<String> argv, String flag, String value) {
        argv.add(flag);
        argv.add(value);
    }
}
