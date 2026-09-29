package io.aria.conductor.execution.runtime;

import io.aria.conductor.execution.adk.opencode.OpenCodeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Controlled launch configuration of one run (spec 6.2), produced from the
 * verified per-core profile and the run-owned configuration directory of the
 * {@link PreparedEnvironment}.
 *
 * <p>Invocation facts are frozen from the committed capability evidence
 * ({@code docs/reviews/2026-09-22-agent-core-capability-evidence.md}, matrix
 * {@code e2e/agent-core/fixtures/capability-matrix.json}):
 * <ul>
 *   <li>Qoder — {@code --acp --config-dir <run>/config --setting-sources project
 *       --strict-mcp-config --mcp-config {"mcpServers":{}}}; the approved MCP
 *       source is the empty inline set, so repository MCP configuration cannot
 *       load, and the credential reaches the child only as
 *       {@code QODER_PERSONAL_ACCESS_TOKEN} in the environment.</li>
 *   <li>OpenCode — {@code serve --port <endpoint port> --hostname <endpoint
 *       host>} with run-owned {@code XDG_CONFIG_HOME}/{@code XDG_DATA_HOME}/
 *       {@code XDG_CACHE_HOME}, so the operator's personal provider store is
 *       never inherited; the model-provider credential keeps coming from the
 *       existing {@code opencode.sandbox-env} resolution rather than a second
 *       copy.</li>
 * </ul>
 *
 * <p>The child environment is an explicit allowlist: a fixed OS variable set
 * (the minimum a Node/CLI child needs to resolve its executable and runtime),
 * run-owned {@code HOME}/{@code USERPROFILE}/{@code TMP}/{@code TEMP} roots
 * inside the run configuration directory, the selected core's model-provider
 * resolution and finally the credential bundle. Personal HOME/config, SDK
 * entrypoint payloads, arbitrary {@code NODE_OPTIONS} and ambient MCP
 * configuration are never inherited, and a credential bundle carrying a name
 * that is not allowed for the selected core is refused instead of injected.
 *
 * <p>The OS set is a <em>derived</em> policy, not a captured one: Task 1
 * recorded the child environment of the probe (run-owned HOME/TMP roots and
 * the SDK scrub list) but no allowlist, so this class states the minimal set
 * explicitly and it remains unverified against the real CLI. Because that set
 * gates every real launch, it is overridable without a code change through
 * {@code aria.cores.launch-system-variables} (comma-separated names; the
 * configured value <em>replaces</em> the derived default; blank means the
 * default). An override can only widen executable/runtime lookup names: a name
 * from the recorded scrub list, {@code NODE_OPTIONS}, a capability or
 * process-injection vector ({@code SSH_AUTH_SOCK}, {@code GIT_ASKPASS},
 * {@code KUBECONFIG}, {@code DOCKER_HOST}) or any credential-shaped name
 * (TOKEN/SECRET/PASSWORD/CREDENTIAL/{@code _KEY}/APIKEY) is refused at
 * construction, loudly, instead of being silently dropped. The refusal is
 * case-insensitive, because the ambient lookup it guards is: on Windows
 * {@code System.getenv("node_options")} resolves the ambient
 * {@code NODE_OPTIONS}, so an exact-case comparison would re-admit exactly the
 * variable this policy exists to block. Real-CLI
 * verification of the final allowlist belongs to the live acceptance matrix.
 *
 * <p>Generated directories always live under the run-owned configuration
 * directory, never inside a Direct user repository, and the Direct workspace's
 * bytes are never touched; the containment decision is canonical and
 * two-directional (a configuration directory inside the workspace and a
 * workspace inside the configuration directory are both refused, links and
 * junctions included). This service performs no process, network or model
 * call, so it can back configuration readiness without spending inference.
 */
@Service
public class CoreConfigurationService {

    public static final String QODER_CORE_ID = "qoder";
    public static final String OPENCODE_CORE_ID = "opencode";

    /** The Qoder runtime credential variable, the only credential variable a qoder child may receive. */
    public static final String QODER_CREDENTIAL_VARIABLE = "QODER_PERSONAL_ACCESS_TOKEN";

    /**
     * The default OS allowlist (DERIVED, not captured from Task 1): the minimum
     * executable/runtime lookup surface a Node/CLI child needs, with no HOME, no
     * APP_DATA, no credential-shaped name and nothing this process's operator has
     * personalized. Overridable via {@code aria.cores.launch-system-variables};
     * any override is still validated against {@link #isForbiddenLaunchVariable}.
     */
    public static final List<String> DEFAULT_ALLOWED_SYSTEM_VARIABLES = List.of(
            "PATH", "PATHEXT", "SystemRoot", "SystemDrive", "windir", "COMSPEC",
            "NUMBER_OF_PROCESSORS", "PROCESSOR_ARCHITECTURE", "OS", "LANG", "LC_ALL", "TZ");

    /**
     * SDK-entrypoint, process-injection and capability variables that must never
     * reach a core child: the recorded scrub list of the capability evidence
     * plus {@code NODE_OPTIONS} and the injection/capability vectors the
     * credential-shaped heuristic cannot see ({@code SSH_AUTH_SOCK},
     * {@code GIT_ASKPASS}, {@code KUBECONFIG}, {@code DOCKER_HOST}).
     */
    static final Set<String> FORBIDDEN_ENVIRONMENT_VARIABLES = Set.of(
            "QODERCLI_RUNTIME_PACKAGING",
            "QODER_AGENT_SDK_ENTRYPOINT",
            "QODER_AGENT_SDK_VERSION",
            "QODER_SDK_AUTH_PAYLOAD_FILE",
            "QODER_SESSION_TYPE",
            "QODER_WORKER_CWD",
            "QODER_WORKER_RUNTIME_ASSET_ROOT",
            "NODE_OPTIONS",
            "SSH_AUTH_SOCK",
            "GIT_ASKPASS",
            "KUBECONFIG",
            "DOCKER_HOST");

    private final String qoderExecutable;
    private final String openCodeExecutable;
    /** Existing OpenCode model-provider resolution ({@code opencode.sandbox-env}); never duplicated. */
    private final Map<String, String> openCodeModelProviderEnvironment;
    /** Effective OS allowlist: the derived default or the validated configured override. */
    private final List<String> allowedSystemVariables;

    /**
     * Production wiring. The allowlist is the derived default unless
     * {@code aria.cores.launch-system-variables} replaces it (comma-separated).
     */
    @Autowired
    public CoreConfigurationService(
            @Value("${aria.cores.qoder.executable:}") String qoderExecutable,
            @Value("${aria.cores.opencode.executable:}") String openCodeExecutable,
            @Value("${aria.cores.launch-system-variables:}") String launchSystemVariables,
            OpenCodeProperties openCodeProperties) {
        this(qoderExecutable, openCodeExecutable, openCodeProperties.getSandboxEnv(),
                parseAllowedSystemVariables(launchSystemVariables));
    }

    /** Test/override seam: existing provider resolution and the derived default allowlist. */
    public CoreConfigurationService(String qoderExecutable, String openCodeExecutable,
            Map<String, String> openCodeModelProviderEnvironment) {
        this(qoderExecutable, openCodeExecutable, openCodeModelProviderEnvironment,
                DEFAULT_ALLOWED_SYSTEM_VARIABLES);
    }

    /** Test/override seam: an explicit OS allowlist, validated like the configured one. */
    public CoreConfigurationService(String qoderExecutable, String openCodeExecutable,
            Map<String, String> openCodeModelProviderEnvironment, List<String> allowedSystemVariables) {
        this.qoderExecutable = qoderExecutable;
        this.openCodeExecutable = openCodeExecutable;
        this.openCodeModelProviderEnvironment = openCodeModelProviderEnvironment == null
                ? Map.of() : Map.copyOf(openCodeModelProviderEnvironment);
        this.allowedSystemVariables = validateAllowedSystemVariables(allowedSystemVariables);
    }

    /**
     * Builds the immutable launch configuration of one run.
     *
     * @throws IllegalArgumentException for an unknown core, a credential
     *         variable not allowed for the selected core, or a configuration
     *         directory inside the user workspace
     * @throws IllegalStateException when the selected core has no configured
     *         executable, or the endpoint cannot yield the recorded invocation
     */
    public LaunchProfile prepare(ExecutionSpec spec, PreparedEnvironment environment,
            SecretBundle credentials) {
        Objects.requireNonNull(spec, "Execution spec is required");
        Objects.requireNonNull(environment, "Prepared environment is required");
        Objects.requireNonNull(credentials, "Credential bundle is required");

        Path configurationDirectory = Path.of(environment.configurationDirectory())
                .toAbsolutePath().normalize();
        Path workingDirectory = Path.of(environment.workingDirectory())
                .toAbsolutePath().normalize();
        requireOutsideWorkspace(workingDirectory, configurationDirectory);
        requireInjectableCredentialVariables(spec.coreId(), credentials.environment());

        Path home = createDirectories(configurationDirectory.resolve("home"));
        Path tmp = createDirectories(configurationDirectory.resolve("tmp"));
        Path config = createDirectories(configurationDirectory.resolve("config"));

        Map<String, String> environmentVariables = new LinkedHashMap<>();
        for (String name : allowedSystemVariables) {
            String value = System.getenv(name);
            if (value != null) {
                environmentVariables.put(name, value);
            }
        }
        environmentVariables.put("HOME", home.toString());
        environmentVariables.put("USERPROFILE", home.toString());
        environmentVariables.put("TMP", tmp.toString());
        environmentVariables.put("TEMP", tmp.toString());

        List<String> argv;
        if (QODER_CORE_ID.equals(spec.coreId())) {
            argv = qoderArgv(requireExecutable(qoderExecutable, "aria.cores.qoder.executable"),
                    config);
        } else if (OPENCODE_CORE_ID.equals(spec.coreId())) {
            String executable = requireExecutable(openCodeExecutable, "aria.cores.opencode.executable");
            URI endpoint = Objects.requireNonNull(environment.endpoint(),
                    "OpenCode launch requires the prepared protocol endpoint");
            if (endpoint.getPort() < 0 || endpoint.getHost() == null) {
                throw new IllegalStateException(
                        "OpenCode launch requires a host:port endpoint, got: " + endpoint);
            }
            environmentVariables.put("XDG_CONFIG_HOME", config.toString());
            environmentVariables.put("XDG_DATA_HOME",
                    createDirectories(configurationDirectory.resolve("data")).toString());
            environmentVariables.put("XDG_CACHE_HOME",
                    createDirectories(configurationDirectory.resolve("cache")).toString());
            openCodeModelProviderEnvironment.forEach((name, value) -> {
                if (value != null && !value.isBlank()) {
                    environmentVariables.put(name, value);
                }
            });
            argv = List.of(executable, "serve", "--port", String.valueOf(endpoint.getPort()),
                    "--hostname", endpoint.getHost());
        } else {
            throw new IllegalArgumentException("Unknown core: " + spec.coreId());
        }

        // The credential bundle is the last writer: only the selected core's
        // allowed variables, validated above, are ever injected here.
        environmentVariables.putAll(credentials.environment());

        return new LaunchProfile(argv, environmentVariables, workingDirectory.toString());
    }

    /** The recorded qoder invocation: ACP over stdio, run-owned config dir, empty approved MCP set. */
    private static List<String> qoderArgv(String executable, Path config) {
        return List.of(executable, "--acp", "--config-dir", config.toString(),
                "--setting-sources", "project", "--strict-mcp-config",
                "--mcp-config", "{\"mcpServers\":{}}");
    }

    private static String requireExecutable(String executable, String property) {
        if (executable == null || executable.isBlank()) {
            throw new IllegalStateException(
                    "Core executable is not configured (" + property + "); refusing to launch");
        }
        return executable;
    }

    // ------------------------------------------------------ launch environment policy

    /**
     * Parses the configured allowlist override: blank keeps the derived default,
     * otherwise the comma-separated names replace it (trimmed, blanks dropped,
     * duplicates collapsed). The result is validated by the constructor.
     */
    private static List<String> parseAllowedSystemVariables(String configured) {
        if (configured == null || configured.isBlank()) {
            return DEFAULT_ALLOWED_SYSTEM_VARIABLES;
        }
        Set<String> names = new LinkedHashSet<>();
        for (String candidate : configured.split(",")) {
            String name = candidate.trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    /**
     * True for a name no launch allowlist may ever admit: the recorded
     * SDK-entrypoint/process-injection scrub list, the capability vectors the
     * credential heuristic misses, and credential-shaped names -- the credential
     * store is the only way a secret reaches a child.
     *
     * <p>The forbidden-list comparison is case-insensitive, because the ambient
     * lookup it guards is: on Windows {@code System.getenv("node_options")}
     * resolves the ambient {@code NODE_OPTIONS}, so an exact-case comparison
     * would re-admit exactly the variable this method exists to refuse.
     */
    static boolean isForbiddenLaunchVariable(String name) {
        if (name == null || name.isBlank() || isForbiddenEnvironmentVariable(name)) {
            return true;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.contains("TOKEN") || upper.contains("SECRET") || upper.contains("PASSWORD")
                || upper.contains("CREDENTIAL") || upper.endsWith("_KEY") || upper.endsWith("APIKEY");
    }

    /** Case-insensitive membership: the ambient lookup is case-insensitive on Windows. */
    private static boolean isForbiddenEnvironmentVariable(String name) {
        return FORBIDDEN_ENVIRONMENT_VARIABLES.stream().anyMatch(name::equalsIgnoreCase);
    }

    /** Refuses an allowlist that tries to admit a forbidden name, loudly at construction. */
    private static List<String> validateAllowedSystemVariables(List<String> names) {
        List<String> refused = names.stream()
                .filter(CoreConfigurationService::isForbiddenLaunchVariable)
                .toList();
        if (!refused.isEmpty()) {
            throw new IllegalStateException("Launch environment allowlist"
                    + " (aria.cores.launch-system-variables) must not carry"
                    + " credential-shaped or SDK-entrypoint/process-injection names: " + refused);
        }
        return List.copyOf(names);
    }

    // ------------------------------------------------------ path containment

    /**
     * Two-directional canonical refusal: the run configuration directory must not
     * contain, nor be contained by, the user workspace -- whatever way the paths
     * are spelled. Both are resolved to their real location through the deepest
     * existing ancestor, so a link or junction above a not-yet-existing directory
     * cannot disguise it as an unrelated tree.
     */
    private static void requireOutsideWorkspace(Path workingDirectory, Path configurationDirectory) {
        Path canonicalWorkspace = canonicalTarget(workingDirectory);
        Path canonicalConfiguration = canonicalTarget(configurationDirectory);
        if (canonicalConfiguration.startsWith(canonicalWorkspace)
                || canonicalWorkspace.startsWith(canonicalConfiguration)) {
            throw new IllegalArgumentException(
                    "Run configuration directory must be outside the workspace: " + configurationDirectory);
        }
    }

    /**
     * Canonical form of a directory that may not exist yet (the run configuration
     * directory is created by {@link #prepare}): the deepest existing ancestor is
     * resolved to its real path and the remaining components are appended. A
     * resolution failure of an existing ancestor fails closed, and so does an
     * ascent that reaches a filesystem root which does not exist (a stale path
     * such as {@code Z:\config} on a removed drive): there is no real location to
     * anchor the path to, so it is refused clearly instead of failing later.
     */
    private static Path canonicalTarget(Path directory) {
        Path absolute = directory.toAbsolutePath().normalize();
        Deque<Path> missing = new ArrayDeque<>();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) {
            Path name = existing.getFileName();
            if (name == null) {
                throw new IllegalArgumentException("Cannot resolve " + absolute
                        + ": its filesystem root " + existing + " does not exist");
            }
            missing.addFirst(name);
            existing = existing.getParent();
        }
        Path canonical = existing == null ? absolute : realPath(existing);
        for (Path name : missing) {
            canonical = canonical.resolve(name);
        }
        return canonical;
    }

    private static Path realPath(Path directory) {
        try {
            return directory.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Cannot resolve the run configuration workspace path " + directory, e);
        }
    }

    /**
     * Only the selected core's credential variables may be injected: qoder
     * accepts the recorded runtime variable, opencode accepts exactly the
     * variables of the existing model-provider resolution. A bundle carrying
     * anything else -- an SDK entrypoint payload, {@code NODE_OPTIONS}, or
     * another core's credential -- is refused rather than leaked into a child.
     */
    private void requireInjectableCredentialVariables(String coreId, Map<String, String> environment) {
        Set<String> allowed = new LinkedHashSet<>();
        if (QODER_CORE_ID.equals(coreId)) {
            allowed.add(QODER_CREDENTIAL_VARIABLE);
        } else if (OPENCODE_CORE_ID.equals(coreId)) {
            allowed.addAll(openCodeModelProviderEnvironment.keySet());
        }
        for (String name : new TreeSet<>(environment.keySet())) {
            if (isForbiddenEnvironmentVariable(name)) {
                throw new IllegalArgumentException("Credential bundle must not carry " + name
                        + " (SDK-entrypoint/ambient configuration variable)");
            }
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException(
                        "Credential environment variable " + name + " is not allowed for core " + coreId);
            }
        }
    }

    private static Path createDirectories(Path directory) {
        try {
            return Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create run configuration directory " + directory, e);
        }
    }
}
