package io.aria.conductor.execution.runtime.core;

import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.execution.mcp.RunMcpWiring;
import io.aria.conductor.execution.runtime.ControlStrategy;
import io.aria.conductor.execution.runtime.CoreAdapter;
import io.aria.conductor.execution.runtime.CoreCapabilities;
import io.aria.conductor.execution.runtime.CoreConfigurationService;
import io.aria.conductor.execution.runtime.CoreLaunchEnvironment;
import io.aria.conductor.execution.runtime.CoreSession;
import io.aria.conductor.execution.runtime.ExecutionSpec;
import io.aria.conductor.execution.runtime.LaunchProfile;
import io.aria.conductor.execution.runtime.PreparedEnvironment;
import io.aria.conductor.execution.runtime.RuntimeHandle;
import io.aria.conductor.execution.runtime.SecretBundle;
import io.aria.conductor.execution.runtime.sandbox.SandboxBind;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
 * allocated. The two secret-bearing files are materialized before the bridge is
 * started: the trusted launcher writes the run-owned credential file from the
 * resolved bundle (this adapter, at profile construction) and the placement
 * writes the minted control-secret file the profile declares (it alone holds
 * that secret). No secret value ever reaches a command line.
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

    /** Run-owned file the bridge reads the platform-MCP worker token from. */
    static final String WORKER_MCP_TOKEN_FILE = "worker-mcp.token";

    /**
     * Run-owned file the bridge reads secret-bearing child-environment pairs from
     * (F14). Keeping the resolved git credential out of {@code --child-env} keeps
     * it out of the process table.
     */
    static final String CHILD_ENVIRONMENT_FILE = "child-env.env";

    /**
     * The newline of every run-owned secret file. A fixed LF, never the platform
     * separator: the readers are Node processes started on either side of a
     * placement boundary, so the bytes must not change with the host OS.
     */
    public static final String RUN_OWNED_FILE_NEWLINE = "\n";

    private static final CoreCapabilities QODER_HOST =
            new CoreCapabilities(ControlStrategy.BACKEND_SUSPEND, true, false, true);
    private static final CoreCapabilities UNVERIFIED =
            new CoreCapabilities(ControlStrategy.UNVERIFIED, false, false, false);

    private final QoderProfile profile;
    private final RunMcpWiring runMcp;
    /** The run-owned launch environment added to every launch (F10/F12/F13/F14/F20). */
    private final CoreLaunchEnvironment launchEnvironment;

    public QoderCoreAdapter(QoderProfile profile, RunMcpWiring runMcp) {
        this(profile, runMcp, CoreLaunchEnvironment.disabled());
    }

    /**
     * The production constructor: the derived launch environment (effective PATH,
     * resolved git credential, git ceiling and Windows long paths) is added to the
     * reviewed profile. A reviewed profile value always wins over a derived one, so
     * operator configuration is never silently replaced.
     */
    public QoderCoreAdapter(QoderProfile profile, RunMcpWiring runMcp,
            CoreLaunchEnvironment launchEnvironment) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.runMcp = Objects.requireNonNull(runMcp, "runMcp");
        this.launchEnvironment = Objects.requireNonNull(launchEnvironment, "launchEnvironment");
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
     * secret value appears in the argv, and the environment is the reviewed
     * profile environment over the run-owned launch environment (effective PATH,
     * resolved git credential, git ceiling, Windows long paths). A reviewed value
     * wins over a derived one on the same name.
     *
     * <p>Everything the bridge is told must exist when it is started (the
     * committed bridge refuses a {@code --cli} that is not an absolute existing
     * executable and a {@code --control-secret-file}/{@code --credential-file}
     * it cannot read): the bridge entry and the core executable are therefore
     * resolved to absolute existing paths here -- a bare configured name is
     * resolved against this process's {@code PATH} -- and an unresolvable one is
     * refused loudly by property name instead of launching a bridge that can only
     * exit before it binds its endpoint. The run-owned credential file is written
     * here from the credential bundle (the trusted-launcher side of the
     * {@code --credential-file} contract); the control-secret file is declared on
     * the profile and written by the placement, which is the only party that
     * holds the minted secret.
     */
    @Override
    public LaunchProfile launchProfile(ExecutionSpec spec, PreparedEnvironment environment,
            SecretBundle credentials) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(credentials, "credentials");
        requireCore(spec);
        requireMatchingEnvironment(spec, environment);
        // The launch below hands this environment to the bridge process verbatim.
        // A profile constructed directly has not passed the guard where the
        // operator maps are merged, so the guard runs here, before anything is
        // written or spawned: a launcher-owned name must be refused, not copied.
        CoreConfigurationService.requireNoLauncherOwnedVariables(profile.environment(),
                "the reviewed Qoder bridge profile environment");
        URI endpoint = requireLoopbackEndpoint(environment);

        String bridgeEntry = requireExistingFile(profile.bridgeEntry(),
                "aria.cores.qoder.bridge-entry (the built committed bridge entry)");
        String coreExecutable = resolveExecutable(profile.coreExecutable(),
                "aria.cores.qoder.executable (the pinned Qoder CLI)");
        // A sandbox working directory is already absolute IN THE SANDBOX (e.g.
        // /workspace) and must not be host-absolutized: on Windows that turns it into
        // C:workspace, which the launch manifest rightly refuses.
        // A sandbox working directory is a SANDBOX path (e.g. /workspace): it is used
        // verbatim, never converted through the host filesystem (a Windows Path turns it
        // into workspace, which the launch manifest refuses). A host working directory
        // is host-absolutized as before.
        String workingDirectory = SandboxBind.isSandboxProxy(environment)
                ? environment.workingDirectory()
                : Path.of(environment.workingDirectory()).toAbsolutePath().normalize().toString();
        List<String> argv = new ArrayList<>();
        argv.add(profile.nodeExecutable());
        argv.add(bridgeEntry);
        add(argv, "--run-id", spec.runId().toString());
        add(argv, "--workspace", workingDirectory.toString());
        add(argv, "--model", profile.model());
        argv.add("--cli");
        argv.add(coreExecutable);
        for (String argument : profile.coreArguments()) {
            argv.add("--cli-arg");
            argv.add(argument);
        }
        // Deterministic order: the child environment is a set of pairs, and a
        // stable rendering keeps one reviewed profile one launch profile. The
        // derived run-owned values are laid under the reviewed profile's, so an
        // operator value always wins (F10/F12/F13/F14/F20 are the derived ones).
        // A derived value describes the HOST placement only (host PATH, host
        // credentials); a SANDBOX launch gets no host-derived entry.
        Map<String, String> derivedEnvironment = spec.mode() == ExecutionMode.HOST
                ? launchEnvironment.childEnvironment(workingDirectory) : Map.of();
        // Mutable copy: the derived and reviewed maps are immutable, and the git
        // credential is removed from this view below.
        Map<String, String> childEnvironment = new LinkedHashMap<>(
                overlay(derivedEnvironment, profile.childEnvironment()));
        // F14: --child-env pairs are argv, hence readable from the process table.
        // The git credential is routed through a run-owned file instead; it must
        // never be rendered into the command line. The file lives on the HOST, so
        // this routing is HOST-only by construction: a SANDBOX bridge runs inside
        // the sandbox and cannot read a host-owned path, and a reviewed profile
        // token pair keeps its prior inline --child-env behavior there.
        Map<String, String> fileEnvironment = new LinkedHashMap<>();
        if (spec.mode() == ExecutionMode.HOST) {
            for (String tokenName : List.of(CoreLaunchEnvironment.GH_TOKEN_VARIABLE,
                    CoreLaunchEnvironment.GITHUB_TOKEN_VARIABLE)) {
                String value = childEnvironment.remove(tokenName);
                if (value != null) {
                    fileEnvironment.put(tokenName, value);
                }
            }
        }
        for (Map.Entry<String, String> entry : new TreeMap<>(childEnvironment).entrySet()) {
            add(argv, "--child-env", entry.getKey() + "=" + entry.getValue());
        }
        if (!fileEnvironment.isEmpty()) {
            Path childEnvironmentFile = writeChildEnvironmentFile(environment, fileEnvironment);
            add(argv, "--child-env-file", childEnvironmentFile.toString());
        }
        Map<String, String> credentialEnvironment = credentialEnvironment(credentials);
        // The values this launch injects as secrets: the bundle's values (written to
        // the run-owned credential file), the run-scoped MCP bearer (written to
        // its token file) and the resolved git credential carried in the child
        // environment. The log reads them from this profile, one launch object.
        Set<String> secretValues = new LinkedHashSet<>(credentials.environment().values());
        secretValues.addAll(launchEnvironment.secretValues());
        if (!credentialEnvironment.isEmpty()) {
            if (credentialEnvironment.size() > 1) {
                throw new IllegalArgumentException("The Qoder bridge takes exactly one credential variable, got "
                        + credentialEnvironment.keySet());
            }
            Map.Entry<String, String> credential = credentialEnvironment.entrySet().iterator().next();
            Path credentialFile = writeCredentialFile(environment, credential.getValue());
            add(argv, "--credential-env", credential.getKey());
            add(argv, "--credential-file", credentialFile.toString());
        }
        // A bundle that omits every credential variable is tolerated on purpose
        // (no --credential-env/--credential-file pair is emitted): such a child
        // cannot authenticate and will fail at its first authenticated
        // handshake. Provisioning the run's credential -- or refusing the run
        // before it starts -- is the configuration service's responsibility,
        // carried into the coordinator wiring, not this adapter's.
        Path controlSecretFile = controlSecretFile(environment);
        add(argv, "--control-secret-file", controlSecretFile.toString());
        // SANDBOX: the endpoint is the server's proxy URL for the in-sandbox port, so
        // the bridge listens on the sandbox's own loopback at the inner port the proxy
        // forwards to (the coordinator dials the proxy URL as the session endpoint).
        // HOST: the endpoint IS the bridge's listen address.
        if (SandboxBind.isSandboxProxy(environment)) {
            add(argv, "--host", SandboxBind.loopbackHost());
            add(argv, "--port", String.valueOf(SandboxBind.innerPort(endpoint)));
        } else {
            add(argv, "--host", endpoint.getHost());
            add(argv, "--port", String.valueOf(endpoint.getPort()));
        }
        Optional<RunMcpWiring.Endpoint> workerMcp = runMcp.forRun(spec, environment);
        if (workerMcp.isPresent()) {
            // The bridge reads the token file at startup and refuses one that is
            // not readable, so the run-owned file is materialized here (the
            // trusted-launcher side of the contract); the token itself never
            // reaches the argv.
            Path tokenFile = writeWorkerMcpTokenFile(environment, workerMcp.get().token());
            secretValues.add(workerMcp.get().token());
            add(argv, "--worker-mcp-name", "aria-conductor");
            add(argv, "--worker-mcp-url", workerMcp.get().url());
            add(argv, "--worker-mcp-token-file", tokenFile.toString());
        }
        Map<String, String> bridgeEnvironment = spec.mode() == ExecutionMode.HOST
                ? launchEnvironment.bridgeEnvironment() : Map.of();
        return new LaunchProfile(argv, overlay(bridgeEnvironment, profile.environment()),
                workingDirectory.toString(), controlSecretFile.toString(), secretValues);
    }

    /**
     * The derived values under the reviewed ones: the reviewed profile is operator
     * configuration, so it wins on a name both sides set.
     */
    private static Map<String, String> overlay(Map<String, String> derived, Map<String, String> reviewed) {
        if (derived.isEmpty()) {
            return reviewed;
        }
        Map<String, String> merged = new LinkedHashMap<>(derived);
        merged.putAll(reviewed);
        return merged;
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

    /** The run-owned file the profile names for the platform-MCP worker token. */
    public static Path workerMcpTokenFile(PreparedEnvironment environment) {
        return Path.of(environment.configurationDirectory()).toAbsolutePath().normalize()
                .resolve(WORKER_MCP_TOKEN_FILE);
    }

    /** The run-owned file the profile names for secret child-environment pairs. */
    public static Path childEnvironmentFile(PreparedEnvironment environment) {
        return Path.of(environment.configurationDirectory()).toAbsolutePath().normalize()
                .resolve(CHILD_ENVIRONMENT_FILE);
    }

    // ------------------------------------------------------ launch-time existence gates

    /**
     * The configured file, resolved to an absolute existing path. A relative
     * configured value is resolved in two places, in order: the process working
     * directory (an operator value relative to where the backend was started), then
     * the repository root (F3 -- the shipped default
     * {@code packages/qoder-acp-bridge/dist/main.js} is repository-relative, while
     * {@code mvn spring-boot:run -pl act-app} starts the process in {@code act-app/}).
     * A missing file is refused loudly by property name: the bridge would only exit
     * before it binds its endpoint, which at the readiness gate is indistinguishable
     * from a slow one.
     */
    static String requireExistingFile(String configured, String property) {
        return resolveBridgeEntry(configured, Path.of("").toAbsolutePath(),
                repositoryRoot(Path.of("").toAbsolutePath()), property);
    }

    /**
     * The bridge-entry resolution with its lookup locations as explicit inputs (test
     * seam). The failure names every location tried and the exact command that
     * builds the missing artifact -- it must not recommend "unset the property",
     * because the default is one of the values that just failed.
     */
    static String resolveBridgeEntry(String configured, Path workingDirectory, Path repositoryRoot) {
        return resolveBridgeEntry(configured, workingDirectory, repositoryRoot,
                "aria.cores.qoder.bridge-entry (the built committed bridge entry)");
    }

    static String resolveBridgeEntry(String configured, Path workingDirectory, Path repositoryRoot,
            String property) {
        Path spelled = Path.of(configured);
        List<Path> candidates = new ArrayList<>();
        if (spelled.isAbsolute()) {
            candidates.add(spelled.normalize());
        } else {
            candidates.add(workingDirectory.toAbsolutePath().normalize().resolve(spelled).normalize());
            if (repositoryRoot != null) {
                Path repositoryCandidate = repositoryRoot.toAbsolutePath().normalize()
                        .resolve(spelled).normalize();
                if (!candidates.contains(repositoryCandidate)) {
                    candidates.add(repositoryCandidate);
                }
            }
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        throw new IllegalStateException("The Qoder bridge launch requires an existing file for " + property
                + ", but the configured value does not resolve to one: " + configured + " (tried: "
                + candidates + "). Build the committed bridge with"
                + " `pnpm --dir packages/qoder-acp-bridge build` and point the property at the built file, or"
                + " keep the repository-relative default and start the backend from inside the repository.");
    }

    /**
     * The repository root above a starting directory (F3): the nearest ancestor that
     * carries the committed bridge package, the backend reactor or a git directory.
     * Empty when the start directory is outside the repository, in which case a
     * relative bridge entry can only be resolved against the working directory.
     */
    static Path repositoryRoot(Path start) {
        Path directory = start.toAbsolutePath().normalize();
        while (directory != null) {
            if (Files.exists(directory.resolve("packages/qoder-acp-bridge/package.json"))
                    || Files.exists(directory.resolve("agent-control-tower/pom.xml"))
                    || Files.exists(directory.resolve(".git"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        return null;
    }

    /**
     * The configured core executable, resolved to the absolute existing file the
     * committed bridge demands for {@code --cli}: an absolute configured path must
     * exist, a bare name is resolved against this process's {@code PATH}, and (F4) a
     * bare name that misses PATH is looked up in the released CLI's install
     * directories, where the executable is versioned
     * ({@code ~/.qoder/bin/qodercli/qodercli-<version>.exe}) -- the shipped default
     * {@code qoder} does not resolve on Windows without that discovery. With no
     * absolute answer on disk the launch is refused loudly by property name instead
     * of handing the bridge a value it must reject.
     */
    static String resolveExecutable(String configured, String property) {
        return resolveExecutable(configured, property, System.getenv("PATH"), System.getenv("PATHEXT"),
                defaultDiscoveryRoots());
    }

    /** The directories the released CLI installs its versioned executable in. */
    static List<Path> defaultDiscoveryRoots() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            return List.of();
        }
        Path bin = Path.of(home, ".qoder", "bin");
        return List.of(bin.resolve("qodercli"), bin);
    }

    /**
     * The resolution itself, with the lookup environment as explicit inputs (test
     * seam): the configured path when it is an existing absolute file, else the
     * first {@code PATH} candidate that is one, else (bare names only) the highest
     * versioned executable in a discovery root. On Windows (an extension set is
     * present) the {@code PATH} candidates are only the spawnable images, so an
     * extensionless shim cannot shadow the executable the bridge must start.
     */
    static String resolveExecutable(String configured, String property, String pathEnvironment,
            String pathExtensions) {
        return resolveExecutable(configured, property, pathEnvironment, pathExtensions,
                defaultDiscoveryRoots());
    }

    static String resolveExecutable(String configured, String property, String pathEnvironment,
            String pathExtensions, List<Path> discoveryRoots) {
        boolean windows = pathExtensions != null && !pathExtensions.isBlank();
        Path path = Path.of(configured);
        if (path.isAbsolute()) {
            // The committed bridge spawns the CLI without a shell, so an absolute
            // configured .cmd/.bat wrapper or an extensionless shim is just as
            // unspawnable as a PATH one: refuse it by property name instead of
            // passing it through because the file exists.
            if (windows && !spawnableImage(path)) {
                throw new IllegalStateException("The Qoder bridge launch requires a spawnable absolute executable"
                        + " for " + property + ", but the configured " + configured + " is not a .exe/.com image:"
                        + " on Windows only .exe/.com can be started without a shell (an extensionless shim and"
                        + " a .cmd/.bat wrapper cannot), so configure the absolute path of the pinned Qoder CLI"
                        + " (aria.cores.qoder.executable)");
            }
            if (!Files.isRegularFile(path)) {
                throw new IllegalStateException("The Qoder bridge launch requires an absolute existing executable"
                        + " for " + property + ", but the configured " + configured + " does not exist");
            }
            return path.normalize().toString();
        }
        for (Path candidate : pathCandidates(configured, pathEnvironment, pathExtensions)) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        if (!configured.contains("/") && !configured.contains("\\")) {
            Optional<Path> discovered = discoverVersionedExecutable(configured, discoveryRoots, windows);
            if (discovered.isPresent()) {
                return discovered.get().toAbsolutePath().normalize().toString();
            }
        }
        throw new IllegalStateException("The Qoder bridge launch requires an absolute existing executable for "
                + property + ", and the configured name '" + configured + "' does not resolve to one on PATH or"
                + " in the released CLI directories " + discoveryRoots + "; on Windows only .exe/.com images are"
                + " spawnable without a shell (an extensionless shim and a .cmd/.bat wrapper are not), so configure"
                + " the absolute path of the pinned Qoder CLI (aria.cores.qoder.executable)");
    }

    /** True for the two file names Windows starts without a shell (.exe/.com). */
    private static boolean spawnableImage(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".exe") || name.endsWith(".com");
    }

    /**
     * The highest-versioned executable of a bare CLI name in the release
     * directories: a file named {@code <name>[-<version>][.exe]}. Version segments
     * compare numerically, so {@code 1.1.61} beats {@code 1.1.9}. On Windows
     * ({@code spawnableImagesOnly}) the population is the spawnable images only,
     * exactly like the {@code PATH} lookup -- discovery is not a way around the
     * rule the refusal states.
     */
    static Optional<Path> discoverVersionedExecutable(String name, List<Path> roots,
            boolean spawnableImagesOnly) {
        List<Path> matches = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var entries = Files.list(root)) {
                entries.filter(Files::isRegularFile)
                        .filter(entry -> matchesName(entry.getFileName().toString(), name, spawnableImagesOnly))
                        .forEach(matches::add);
            } catch (IOException e) {
                // An unreadable discovery directory is not evidence of absence; the
                // remaining roots and the refusal below still apply.
            }
        }
        return matches.stream().max((left, right) -> {
            int version = compareVersionKeys(versionKey(left), versionKey(right));
            return version != 0 ? version : left.getFileName().toString().compareTo(right.getFileName().toString());
        });
    }

    /** Segment-wise numeric comparison: 1.1.61 is newer than 1.1.9, and a missing segment is 0. */
    private static int compareVersionKeys(List<Integer> left, List<Integer> right) {
        int length = Math.max(left.size(), right.size());
        for (int i = 0; i < length; i++) {
            int a = i < left.size() ? left.get(i) : 0;
            int b = i < right.size() ? right.get(i) : 0;
            if (a != b) {
                return Integer.compare(a, b);
            }
        }
        return 0;
    }

    /**
     * True for the released CLI of the configured name: {@code <name>},
     * {@code <name>cli} or {@code <name>-cli}, optionally followed by
     * {@code -<version>}, with an optional executable extension. The released
     * Windows CLI is {@code qodercli-<version>.exe}, while the documented property
     * default is the bare {@code qoder}. On Windows only the spawnable images (a
     * {@code .exe}/{@code .com} file) match: discovery must never select the
     * wrapper the refusal calls unspawnable.
     */
    private static boolean matchesName(String fileName, String name, boolean spawnableImagesOnly) {
        String stem = fileName.toLowerCase(java.util.Locale.ROOT);
        int dot = stem.lastIndexOf('.');
        if (dot > 0) {
            String extension = stem.substring(dot);
            boolean spawnable = extension.equals(".exe") || extension.equals(".com");
            if (spawnableImagesOnly ? !spawnable
                    : !spawnable && !extension.equals(".cmd") && !extension.equals(".bat")) {
                return false;
            }
            stem = stem.substring(0, dot);
        } else if (spawnableImagesOnly) {
            // CreateProcess appends .exe to an extensionless name, so the file that
            // actually starts is not this one.
            return false;
        }
        String lowerName = name.toLowerCase(java.util.Locale.ROOT);
        for (String base : List.of(lowerName, lowerName + "cli", lowerName + "-cli")) {
            if (!stem.startsWith(base)) {
                continue;
            }
            String rest = stem.substring(base.length());
            if (rest.isEmpty()) {
                return true;
            }
            if (rest.startsWith("-") && rest.substring(1).matches("\\d+(?:\\.\\d+)*")) {
                return true;
            }
        }
        return false;
    }

    /** The numeric version segments of a file name; a name without one sorts lowest. */
    private static List<Integer> versionKey(Path path) {
        String fileName = path.getFileName().toString();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)*)").matcher(fileName);
        String version = matcher.find() ? matcher.group(1) : "0";
        List<Integer> segments = new ArrayList<>();
        for (String segment : version.split("\\.")) {
            try {
                segments.add(Integer.parseInt(segment));
            } catch (NumberFormatException e) {
                segments.add(0);
            }
        }
        return segments;
    }

    /**
     * A bare name on every {@code PATH} directory. The committed bridge spawns the
     * CLI without a shell, so on Windows ({@code pathExtensions} non-blank) only a
     * spawnable image is a candidate: an extensionless Unix shim -- the shape the
     * released Qoder package also installs on {@code PATH} -- or a
     * {@code .cmd}/{@code .bat} wrapper cannot be started by {@code CreateProcess}
     * and must never shadow the executable the bridge can actually run. A POSIX
     * lookup (no extension set) keeps the bare name exactly as configured.
     */
    private static List<Path> pathCandidates(String name, String pathEnvironment, String pathExtensions) {
        List<Path> candidates = new ArrayList<>();
        if (pathEnvironment == null || pathEnvironment.isBlank()) {
            return candidates;
        }
        List<String> names = spawnableNames(name, pathExtensions != null && !pathExtensions.isBlank());
        for (String directory : pathEnvironment.split(java.io.File.pathSeparator)) {
            if (directory.isBlank()) {
                continue;
            }
            Path root = Path.of(directory);
            for (String candidate : names) {
                candidates.add(root.resolve(candidate));
            }
        }
        return candidates;
    }

    /**
     * The file names one {@code PATH} directory can be probed for: the bare name on
     * POSIX and for a name that already spells an image, else only the two
     * extensions Windows starts without a shell. The other {@code PATHEXT} entries
     * ({@code .bat}, {@code .cmd}, {@code .vbs}) require a shell and are skipped.
     */
    private static List<String> spawnableNames(String name, boolean windowsExtensions) {
        if (!windowsExtensions) {
            return List.of(name);
        }
        String lowerName = name.toLowerCase(java.util.Locale.ROOT);
        if (lowerName.endsWith(".exe") || lowerName.endsWith(".com")) {
            return List.of(name);
        }
        return List.of(name + ".exe", name + ".com");
    }

    /**
     * Writes the run-owned credential file the profile names ({@code mode}-agnostic:
     * the trusted-launcher side of the bridge contract). The file lives in the
     * run-owned configuration directory, never in the user workspace, and the value
     * reaches the core only through the file the bridge reads -- never the argv.
     */
    private static Path writeCredentialFile(PreparedEnvironment environment, String credential) {
        Path target = credentialFile(environment);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, credential + RUN_OWNED_FILE_NEWLINE, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write the run-owned credential file " + target
                    + "; the bridge would be told to read a file that does not exist", e);
        }
        restrictToOwner(target);
        return target;
    }

    /**
     * Writes the run-owned platform-MCP worker token file the profile names,
     * mirroring {@link #writeCredentialFile}: the file lives in the run-owned
     * configuration directory, ends in the fixed LF, and the value reaches the
     * bridge only through the file — never the argv.
     */
    private static Path writeWorkerMcpTokenFile(PreparedEnvironment environment, String token) {
        Path target = workerMcpTokenFile(environment);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, token + RUN_OWNED_FILE_NEWLINE, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write the run-owned platform MCP token file " + target
                    + "; the bridge would be told to read a file that does not exist", e);
        }
        restrictToOwner(target);
        return target;
    }

    /**
     * Writes the run-owned child-environment file the bridge reads (F14): one
     * sorted {@code KEY=VALUE} line per entry, fixed LF. Used for secret-bearing
     * pairs (the resolved git credential) that must not appear in argv.
     */
    private static Path writeChildEnvironmentFile(PreparedEnvironment environment,
            Map<String, String> entries) {
        Path target = childEnvironmentFile(environment);
        StringBuilder content = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(entries).entrySet()) {
            content.append(entry.getKey()).append('=').append(entry.getValue())
                    .append(RUN_OWNED_FILE_NEWLINE);
        }
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write the run-owned child environment file " + target
                    + "; the bridge would be told to read a file that does not exist", e);
        }
        restrictToOwner(target);
        return target;
    }

    /**
     * Restricts a run-owned secret file to its owner ({@code rw-------}). The files
     * hold the model credential, the run bearer and the git credential, so their
     * default umask-derived mode is not good enough where POSIX modes exist. A
     * filesystem without them (Windows) keeps its platform default; any other
     * failure refuses the launch rather than leaving a secret world-readable.
     */
    private static void restrictToOwner(Path target) {
        try {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // No POSIX mode on this filesystem (Windows): nothing to restrict.
        } catch (IOException e) {
            throw new IllegalStateException("Unable to restrict the run-owned secret file " + target
                    + " to its owner", e);
        }
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
     * The endpoint the run was prepared with must be loopback — a HOST placement's
     * bridge listen address, or a SANDBOX placement's proxy URL for the in-sandbox
     * port ({@code http://localhost:<published>/proxy/<port>}, whose host the
     * server's {@code eip} setting names as {@code localhost}). The bridge's own
     * {@code --host} accepts exactly its two literals, so a sandbox run binds
     * {@link SandboxBind#loopbackHost()} at the proxied inner port instead of this
     * endpoint's host/port (see {@code launchProfile}).
     */
    private static URI requireLoopbackEndpoint(PreparedEnvironment environment) {
        URI endpoint = Objects.requireNonNull(environment.endpoint(),
                "The Qoder launch requires the prepared protocol endpoint");
        String host = endpoint.getHost();
        // A HOST endpoint is the bridge's own listen address, so the bridge's two
        // literals are the contract; a SANDBOX endpoint is the server's proxy URL,
        // whose host the server's eip setting names (localhost), and the bridge gets
        // SandboxBind's literal instead.
        boolean loopback = "127.0.0.1".equals(host) || "::1".equals(host)
                || (SandboxBind.isSandboxProxy(environment) && "localhost".equalsIgnoreCase(host));
        if (!loopback) {
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
