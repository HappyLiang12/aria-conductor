package io.aria.conductor.execution.runtime.core;

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

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Qoder core adapter (task 11): the reviewed Qoder profile behind the shared
 * {@link CoreAdapter} port.
 *
 * <p>The runtime at the run's endpoint for this core is the committed run-bound
 * ACP bridge (task 8), which drives the pinned Qoder CLI child over the
 * recorded newline-delimited ACP protocol. The launch profile this adapter
 * produces is therefore the bridge invocation: the reviewed bridge entry, the
 * run binding, the reviewed core executable and its recorded arguments, the
 * run-owned child environment, the run's control-secret and credential files
 * (named, never carried as arguments) and the loopback endpoint the backend
 * allocated. The trusted launcher writes those two files from the run's secret
 * material before it starts the profile -- nothing here writes a secret to
 * disk or puts one on a command line.
 *
 * <p>Capabilities are the reviewed rows of the committed capability matrix
 * ({@code e2e/agent-core/fixtures/capability-matrix.json}), never inferred from
 * a live core: {@code qoder/HOST} is verified (pause/resume verified as
 * process suspension of the owned tree with {@code nativePauseRpcUsed=false},
 * cooperative cancel verified, usage observable in the prompt result), so the
 * strategy snapshot is {@link ControlStrategy#BACKEND_SUSPEND}. Every other
 * core/mode row is blocked -- nothing was observed for it -- so its values stay
 * false/{@link ControlStrategy#UNVERIFIED} instead of being assumed. No row
 * records an enforced round limit.
 *
 * <p>Session methods allocate nothing: {@link #open} connects to the endpoint
 * the launch produced and {@link #launchProfile} only describes the launch.
 */
public final class QoderCoreAdapter implements CoreAdapter {

    public static final String CORE_ID = "qoder";

    /**
     * Environment variable the trusted launcher delivers the run's bridge
     * control secret in (the same name the host backend uses). The bridge
     * itself reads the secret from its {@code --control-secret-file} and never
     * forwards it to the CLI child.
     */
    public static final String CONTROL_SECRET_ENVIRONMENT = "QODER_BRIDGE_CONTROL_SECRET";

    /** Run-owned file the bridge reads its per-run control secret from. */
    static final String CONTROL_SECRET_FILE = "bridge-control.secret";

    /** Run-owned file the bridge reads the core credential from. */
    static final String CREDENTIAL_FILE = "credential.secret";

    private static final CoreCapabilities QODER_HOST =
            new CoreCapabilities(ControlStrategy.BACKEND_SUSPEND, true, false, true);
    private static final CoreCapabilities UNVERIFIED =
            new CoreCapabilities(ControlStrategy.UNVERIFIED, false, false, false);

    private final QoderProfile profile;

    public QoderCoreAdapter(QoderProfile profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    /**
     * The reviewed Qoder profile: the pinned artifacts of the core and of the
     * committed bridge, the recorded CLI invocation, the reviewed child
     * environment and the model pin. Every value is reviewed input -- the
     * adapter invents neither an executable nor an environment entry.
     *
     * @param nodeExecutable    the pinned Node runtime that runs the bridge
     * @param bridgeEntry       absolute path of the committed bridge entry (dist/main.js)
     * @param coreExecutable    the pinned Qoder CLI executable the bridge spawns
     * @param coreArguments     the recorded ACP invocation arguments of the CLI
     * @param childEnvironment  reviewed child environment additions (delivered
     *                          to the core child through {@code --child-env})
     * @param environment       the reviewed bridge-process environment
     * @param coreVersion       the reviewed pinned core version
     * @param model             the reviewed model pin (the requested model)
     */
    public record QoderProfile(String nodeExecutable, String bridgeEntry, String coreExecutable,
            List<String> coreArguments, Map<String, String> childEnvironment,
            Map<String, String> environment, String coreVersion, String model) {

        public QoderProfile {
            nodeExecutable = requireText(nodeExecutable, "nodeExecutable");
            bridgeEntry = requireText(bridgeEntry, "bridgeEntry");
            coreExecutable = requireText(coreExecutable, "coreExecutable");
            coreArguments = List.copyOf(Objects.requireNonNull(coreArguments, "coreArguments"));
            childEnvironment = Map.copyOf(Objects.requireNonNull(childEnvironment, "childEnvironment"));
            environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
            coreVersion = requireText(coreVersion, "coreVersion");
            model = requireText(model, "model");
        }

        private static String requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("The reviewed Qoder profile requires a " + name);
            }
            return value;
        }
    }

    @Override
    public String coreId() {
        return CORE_ID;
    }

    /** The reviewed model pin, i.e. the requested model of every prompt. */
    public String model() {
        return profile.model();
    }

    /** The reviewed pinned core version this profile was reviewed against. */
    public String coreVersion() {
        return profile.coreVersion();
    }

    @Override
    public CoreCapabilities capabilities(ExecutionMode mode) {
        Objects.requireNonNull(mode, "mode");
        return switch (mode) {
            case HOST -> QODER_HOST;
            case SANDBOX -> UNVERIFIED;
        };
    }

    /**
     * The Qoder core declares no service-level prerequisite: its bridge and CLI are
     * started per run, and readiness is verified at launch (the adapter requires the
     * reviewed profile and the run's control secret), so there is no long-running
     * service whose probe could fail. This descriptor is the per-core answer the
     * health route serves for {@code qoder} -- a truthful state instead of the 404
     * a provider-only lookup produced.
     */
    @Override
    public boolean serviceHealthy() {
        return true;
    }

    /**
     * Builds the bridge launch profile of one run. The argv is explicit and
     * complete: run binding, reviewed invocation, endpoint, and the paths of
     * the two run-owned secret-bearing files the trusted launcher writes. No
     * secret value appears in the argv, and the environment is exactly the
     * reviewed profile environment -- the adapter adds nothing to it.
     */
    @Override
    public LaunchProfile launchProfile(ExecutionSpec spec, PreparedEnvironment environment,
            SecretBundle credentials) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(credentials, "credentials");
        requireCore(spec);
        requireMatchingEnvironment(spec, environment);
        URI endpoint = requireLoopbackEndpoint(environment);

        Path workingDirectory = Path.of(environment.workingDirectory()).toAbsolutePath().normalize();
        List<String> argv = new ArrayList<>();
        argv.add(profile.nodeExecutable());
        argv.add(profile.bridgeEntry());
        add(argv, "--run-id", spec.runId().toString());
        add(argv, "--workspace", workingDirectory.toString());
        add(argv, "--model", profile.model());
        argv.add("--cli");
        argv.add(profile.coreExecutable());
        for (String argument : profile.coreArguments()) {
            argv.add("--cli-arg");
            argv.add(argument);
        }
        // Deterministic order: the child environment is a set of pairs, and a
        // stable rendering keeps one reviewed profile one launch profile.
        for (Map.Entry<String, String> entry : new TreeMap<>(profile.childEnvironment()).entrySet()) {
            add(argv, "--child-env", entry.getKey() + "=" + entry.getValue());
        }
        Map<String, String> credentialEnvironment = credentialEnvironment(credentials);
        if (!credentialEnvironment.isEmpty()) {
            if (credentialEnvironment.size() > 1) {
                throw new IllegalArgumentException("The Qoder bridge takes exactly one credential variable, got "
                        + credentialEnvironment.keySet());
            }
            Map.Entry<String, String> credential = credentialEnvironment.entrySet().iterator().next();
            add(argv, "--credential-env", credential.getKey());
            add(argv, "--credential-file", credentialFile(environment).toString());
        }
        // A bundle that omits every credential variable is tolerated on purpose
        // (no --credential-env/--credential-file pair is emitted): such a child
        // cannot authenticate and will fail at its first authenticated
        // handshake. Provisioning the run's credential -- or refusing the run
        // before it starts -- is the configuration service's responsibility,
        // carried into the coordinator wiring, not this adapter's.
        add(argv, "--control-secret-file", controlSecretFile(environment).toString());
        add(argv, "--host", endpoint.getHost());
        add(argv, "--port", String.valueOf(endpoint.getPort()));
        return new LaunchProfile(argv, profile.environment(), workingDirectory.toString());
    }

    /**
     * Opens the session against the endpoint the launch produced. The run's
     * bridge control secret is required in the credential bundle under
     * {@value #CONTROL_SECRET_ENVIRONMENT}: without it the session could not
     * authenticate and must not be opened on trust.
     */
    @Override
    public CoreSession open(RuntimeHandle handle, ExecutionSpec spec, SecretBundle credentials) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(credentials, "credentials");
        requireCore(spec);
        if (!spec.runId().equals(handle.runId())) {
            throw new IllegalArgumentException("The runtime handle belongs to run " + handle.runId()
                    + ", not the execution spec's run " + spec.runId());
        }
        String controlSecret = credentials.environment().get(CONTROL_SECRET_ENVIRONMENT);
        if (controlSecret == null || controlSecret.isBlank()) {
            throw new IllegalStateException("Run " + spec.runId() + " carries no "
                    + CONTROL_SECRET_ENVIRONMENT + " in its credential bundle; the bridge session cannot be "
                    + "authenticated without the run's control secret");
        }
        QoderBridgeClient client = new QoderBridgeClient(handle.endpoint(), controlSecret);
        try {
            return new QoderCoreSession(spec, client, profile.model());
        } catch (RuntimeException e) {
            client.close();
            throw e;
        }
    }

    /** The run-owned file the profile names for the bridge's control secret. */
    public static Path controlSecretFile(PreparedEnvironment environment) {
        return Path.of(environment.configurationDirectory()).toAbsolutePath().normalize()
                .resolve(CONTROL_SECRET_FILE);
    }

    /** The run-owned file the profile names for the core credential. */
    public static Path credentialFile(PreparedEnvironment environment) {
        return Path.of(environment.configurationDirectory()).toAbsolutePath().normalize()
                .resolve(CREDENTIAL_FILE);
    }

    /** The credential variables of the bundle: everything but the bridge's own control secret. */
    private static Map<String, String> credentialEnvironment(SecretBundle credentials) {
        Map<String, String> credential = new LinkedHashMap<>(credentials.environment());
        credential.remove(CONTROL_SECRET_ENVIRONMENT);
        credential.values().removeIf(value -> value == null || value.isBlank());
        return credential;
    }

    private void requireCore(ExecutionSpec spec) {
        if (!CORE_ID.equals(spec.coreId())) {
            throw new IllegalArgumentException("The Qoder adapter does not serve core " + spec.coreId());
        }
    }

    private static void requireMatchingEnvironment(ExecutionSpec spec, PreparedEnvironment environment) {
        if (environment.runId() == null || !spec.runId().equals(environment.runId())) {
            throw new IllegalArgumentException("The prepared environment belongs to run " + environment.runId()
                    + ", not the execution spec's run " + spec.runId());
        }
        if (spec.mode() != environment.mode()) {
            throw new IllegalArgumentException("The prepared environment is " + environment.mode()
                    + " but the execution spec asks for " + spec.mode());
        }
    }

    /**
     * The bridge binds loopback only, and its {@code --host} accepts exactly the
     * two literals it validates itself. The committed bridge refuses
     * {@code localhost} ({@code packages/qoder-acp-bridge/src/main.ts}), so a
     * name like it is refused here as well instead of being passed on to a bridge
     * process that could only fail its usage check.
     */
    private static URI requireLoopbackEndpoint(PreparedEnvironment environment) {
        URI endpoint = Objects.requireNonNull(environment.endpoint(),
                "The Qoder launch requires the prepared protocol endpoint");
        String host = endpoint.getHost();
        boolean loopback = "127.0.0.1".equals(host) || "::1".equals(host);
        if (!loopback || endpoint.getPort() < 1) {
            throw new IllegalStateException("The Qoder bridge endpoint must be a loopback host:port, got: "
                    + endpoint);
        }
        return endpoint;
    }

    private static void add(List<String> argv, String flag, String value) {
        argv.add(flag);
        argv.add(value);
    }
}
