package io.aria.conductor.execution.runtime.core;

import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.execution.adk.opencode.OpenCodeHttpClient;
import io.aria.conductor.execution.runtime.ControlStrategy;
import io.aria.conductor.execution.runtime.CoreAdapter;
import io.aria.conductor.execution.runtime.CoreCapabilities;
import io.aria.conductor.execution.runtime.CoreSession;
import io.aria.conductor.execution.runtime.ExecutionSpec;
import io.aria.conductor.execution.runtime.LaunchProfile;
import io.aria.conductor.execution.runtime.PreparedEnvironment;
import io.aria.conductor.execution.runtime.sandbox.SandboxBind;
import io.aria.conductor.execution.runtime.RuntimeHandle;
import io.aria.conductor.execution.runtime.SecretBundle;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * OpenCode core adapter (task 11): the reviewed OpenCode profile behind the
 * shared {@link CoreAdapter} port.
 *
 * <p>For this core the runtime at the run's endpoint is the OpenCode server
 * itself ({@code opencode serve --port <p> --hostname <h>}, the recorded
 * invocation), started with run-owned {@code XDG_CONFIG_HOME}/
 * {@code XDG_DATA_HOME}/{@code XDG_CACHE_HOME} roots so the operator's personal
 * provider store, plugins and configuration are never inherited.
 *
 * <p><strong>Governed profile.</strong> The run-owned configuration directory
 * receives a deny-by-default permission policy ({@link #governedConfigurationJson()}).
 * The legacy provider's default ({@code "permission": {"*": "allow"}}) is
 * exactly what must not survive into this profile: an unknown operation is
 * denied rather than auto-approved, only the classified read-only tools are
 * automatic, and every side-effecting tool -- file edits, shell execution,
 * network fetches, sub-agents, questions and directories outside the workspace --
 * is refused in the core itself. A run-bound approve-once decision is delivered
 * by the run policy and the permission coordinator (task 12) and is never
 * implemented by relaxing this file back into allow-all.
 *
 * <p>Capabilities come from the committed capability matrix
 * ({@code e2e/agent-core/fixtures/capability-matrix.json}): both recorded
 * OpenCode rows are BLOCKED -- no model run and no verified process-control
 * workflow was observed for this core -- so every value stays
 * false/{@link ControlStrategy#UNVERIFIED} instead of being assumed from the
 * fact that the peer implements an abort route. The verified pause strategy of
 * this program is backend process suspension, which the run snapshot takes from
 * the backend, not from a capability this core never demonstrated.
 *
 * <p>Session methods allocate nothing: {@link #open} creates one native session
 * on the endpoint the launch produced, and never a container or a user
 * workspace.
 */
public final class OpenCodeCoreAdapter implements CoreAdapter {

    public static final String CORE_ID = "opencode";

    /** Directories of the run-owned generated configuration, under the environment's root. */
    static final String CONFIG_HOME_DIRECTORY = "config";
    static final String DATA_HOME_DIRECTORY = "data";
    static final String CACHE_HOME_DIRECTORY = "cache";

    /**
     * The governed permission policy. Deny by default, explicit read-only
     * allowances, explicit refusals for every side-effecting surface. Tool names
     * follow the reviewed core's tool ids; an unknown tool is covered by the
     * wildcard refusal and can therefore never be auto-approved.
     */
    private static final String GOVERNED_CONFIGURATION_JSON = """
            {
              "$schema": "https://opencode.ai/config.json",
              "permission": {
                "*": "deny",
                "read": "allow",
                "list": "allow",
                "glob": "allow",
                "grep": "allow",
                "edit": "deny",
                "write": "deny",
                "patch": "deny",
                "bash": "deny",
                "webfetch": "deny",
                "task": "deny",
                "question": "deny",
                "external_directory": "deny"
              }
            }
            """;

    private static final CoreCapabilities UNVERIFIED =
            new CoreCapabilities(ControlStrategy.UNVERIFIED, false, false, false);

    private final OpenCodeProfile profile;

    public OpenCodeCoreAdapter(OpenCodeProfile profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    /**
     * The reviewed OpenCode profile: the pinned server invocation and the
     * reviewed base environment. The run-owned XDG roots, the served endpoint
     * and the governed configuration are derived per run; every other value is
     * reviewed input.
     *
     * @param executable      the pinned OpenCode server executable
     * @param entryArguments  arguments the executable needs before {@code serve}
     *                        (empty for the real binary; a launcher script keeps
     *                        its own entry arguments here)
     * @param environment     the reviewed base environment of the server process
     * @param coreVersion     the reviewed pinned core version
     * @param model           the reviewed model pin (the requested model)
     */
    public record OpenCodeProfile(String executable, List<String> entryArguments,
            Map<String, String> environment, String coreVersion, String model) {

        public OpenCodeProfile {
            executable = requireText(executable, "executable");
            entryArguments = List.copyOf(Objects.requireNonNull(entryArguments, "entryArguments"));
            environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
            coreVersion = requireText(coreVersion, "coreVersion");
            model = requireText(model, "model");
        }

        private static String requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("The reviewed OpenCode profile requires a " + name);
            }
            return value;
        }
    }

    @Override
    public String coreId() {
        return CORE_ID;
    }

    /** The reviewed model pin, i.e. the requested model of every message envelope. */
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
        return UNVERIFIED;
    }

    /**
     * Builds the server launch profile of one run: the recorded {@code serve}
     * invocation on the allocated endpoint, run-owned XDG roots, the reviewed
     * base environment and the run's credential environment. The governed
     * configuration is written into the run-owned configuration directory --
     * never into the user workspace and never over an operator's own file.
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

        Path configurationRoot = Path.of(environment.configurationDirectory()).toAbsolutePath().normalize();
        Path configHome = createDirectory(configurationRoot.resolve(CONFIG_HOME_DIRECTORY));
        Path dataHome = createDirectory(configurationRoot.resolve(DATA_HOME_DIRECTORY));
        Path cacheHome = createDirectory(configurationRoot.resolve(CACHE_HOME_DIRECTORY));
        writeGovernedConfiguration(configurationRoot);
        Path workingDirectory = Path.of(environment.workingDirectory()).toAbsolutePath().normalize();

        List<String> argv = new ArrayList<>();
        argv.add(profile.executable());
        argv.addAll(profile.entryArguments());
        argv.add("serve");
        argv.add("--port");
        // SANDBOX: the endpoint is the server's proxy URL for the in-sandbox port, so
        // the server listens on the sandbox's own loopback at the inner port the proxy
        // forwards to (the coordinator dials the proxy URL as the session endpoint).
        // HOST: the endpoint IS the server's listen address.
        argv.add(String.valueOf(SandboxBind.isSandboxProxy(environment)
                ? SandboxBind.innerPort(endpoint)
                : endpoint.getPort()));
        argv.add("--hostname");
        argv.add(SandboxBind.isSandboxProxy(environment)
                ? SandboxBind.loopbackHost()
                : endpoint.getHost());

        Map<String, String> serverEnvironment = new LinkedHashMap<>(profile.environment());
        serverEnvironment.put("XDG_CONFIG_HOME", configHome.toString());
        serverEnvironment.put("XDG_DATA_HOME", dataHome.toString());
        serverEnvironment.put("XDG_CACHE_HOME", cacheHome.toString());
        // The credential bundle is the last writer, exactly as the controlled
        // launch configuration does it; the bundle itself is validated there
        // (task 5) before it reaches this adapter.
        serverEnvironment.putAll(credentials.environment());
        return new LaunchProfile(argv, serverEnvironment, workingDirectory.toString());
    }

    /**
     * Opens the session on the endpoint the launch produced: one native session,
     * created with single-shot semantics so a lost response cannot leave a
     * second native session behind.
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
        OpenCodeHttpClient client = new OpenCodeHttpClient(handle.endpoint().toString());
        try {
            String sessionId = client.openSession(spec.runId().toString());
            return new OpenCodeCoreSession(spec, client, sessionId, profile.model());
        } catch (RuntimeException e) {
            client.close();
            throw e;
        }
    }

    /** The governed configuration file of one run. */
    public static Path governedConfigurationFile(PreparedEnvironment environment) {
        return Path.of(environment.configurationDirectory()).toAbsolutePath().normalize()
                .resolve(CONFIG_HOME_DIRECTORY)
                .resolve("opencode")
                .resolve("opencode.json");
    }

    /** The exact governed permission policy written for every run. */
    public static String governedConfigurationJson() {
        return GOVERNED_CONFIGURATION_JSON;
    }

    private void writeGovernedConfiguration(Path configurationRoot) {
        Path target = configurationRoot.resolve(CONFIG_HOME_DIRECTORY).resolve("opencode").resolve("opencode.json");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, GOVERNED_CONFIGURATION_JSON, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write the governed OpenCode configuration " + target
                    + ": " + e.getMessage(), e);
        }
    }

    private static Path createDirectory(Path directory) {
        try {
            return Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to prepare the run-owned directory " + directory
                    + ": " + e.getMessage(), e);
        }
    }

    private void requireCore(ExecutionSpec spec) {
        if (!CORE_ID.equals(spec.coreId())) {
            throw new IllegalArgumentException("The OpenCode adapter does not serve core " + spec.coreId());
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

    /** The recorded invocation binds to a loopback host and an explicit port. */
    private static URI requireLoopbackEndpoint(PreparedEnvironment environment) {
        URI endpoint = Objects.requireNonNull(environment.endpoint(),
                "The OpenCode launch requires the prepared protocol endpoint");
        String host = endpoint.getHost();
        boolean loopback = "127.0.0.1".equals(host) || "::1".equals(host) || "localhost".equalsIgnoreCase(host);
        if (!loopback || endpoint.getPort() < 1) {
            throw new IllegalStateException("The OpenCode endpoint must be a loopback host:port, got: " + endpoint);
        }
        return endpoint;
    }
}
