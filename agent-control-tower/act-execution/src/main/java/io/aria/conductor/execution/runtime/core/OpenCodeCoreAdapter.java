package io.aria.conductor.execution.runtime.core;

import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.execution.adk.TaskExecutionException;
import io.aria.conductor.execution.adk.opencode.OpenCodeHttpClient;
import io.aria.conductor.execution.mcp.RunMcpWiring;
import io.aria.conductor.execution.runtime.ControlStrategy;
import io.aria.conductor.execution.runtime.CoreAdapter;
import io.aria.conductor.execution.runtime.CoreCapabilities;
import io.aria.conductor.execution.runtime.CoreSession;
import io.aria.conductor.execution.runtime.ExecutionSpec;
import io.aria.conductor.execution.runtime.LaunchProfile;
import io.aria.conductor.execution.runtime.PreparedEnvironment;
import io.aria.conductor.execution.runtime.sandbox.SandboxBind;
import io.aria.conductor.execution.runtime.sandbox.SandboxLifecycle;
import io.aria.conductor.execution.runtime.RuntimeHandle;
import io.aria.conductor.execution.runtime.SecretBundle;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
     * The governed permission policy plus the operator's model provider: the run
     * may only read, and it serves with the provider the operator activated
     * (never opencode's own default, whose free tier refuses this consumer).
     * Deny by default, explicit read-only allowances, explicit refusals for every
     * side-effecting surface. Tool names follow the reviewed core's tool ids; an
     * unknown tool is covered by the wildcard refusal and can therefore never be
     * auto-approved.
     *
     * <p>{@code aria-conductor*} is the run's sanctioned Conductor surface: the
     * wired platform-MCP tools (run dispatch, reports, kanban, knowledge, ...)
     * behind the run-scoped bearer. opencode hides permission-denied tools from
     * the model, so without this allowance the wired platform tools would be
     * invisible to the assistant (observed: an Aria sandbox run answered "the
     * Conductor tools are not available in this session" while the endpoint was
     * connected).
     *
     * <p>The first {@code %s} slot carries the platform-MCP block of a wired run;
     * the remaining slots carry the provider id, the model, the provider id
     * again, the base URL and the model again.
     */
    private static final String GOVERNED_CONFIGURATION_TEMPLATE = """
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
                "external_directory": "deny",
                "aria-conductor*": "allow"
              }%s,
              "model": "%s/%s",
              "provider": {
                "%s": {
                  "npm": "@ai-sdk/openai-compatible",
                  "options": {
                    "apiKey": "{env:LLM_API_KEY}",
                    "baseURL": "%s"
                  },
                  "models": {
                    "%s": {}
                  }
                }
              }
            }
            """;

    private static final CoreCapabilities UNVERIFIED =
            new CoreCapabilities(ControlStrategy.UNVERIFIED, false, false, false);

    /** Budget for polling the launched server's health before a session opens. */
    static final Duration SERVE_READY_BUDGET = Duration.ofSeconds(90);
    /** Poll interval of the serve readiness wait. */
    static final Duration SERVE_READY_POLL = Duration.ofMillis(500);

    private final OpenCodeProfile profile;
    private final RunMcpWiring runMcp;
    private final ModelProviderResolver modelProviders;
    private final Duration serveReadyBudget;
    private final Duration serveReadyPoll;

    public OpenCodeCoreAdapter(OpenCodeProfile profile, RunMcpWiring runMcp) {
        this(profile, runMcp, ModelProviderResolver.fallback(), SERVE_READY_BUDGET, SERVE_READY_POLL);
    }

    /** A launch against an explicit provider resolution (production reads the operator's row). */
    public OpenCodeCoreAdapter(OpenCodeProfile profile, RunMcpWiring runMcp,
            ModelProviderResolver modelProviders) {
        this(profile, runMcp, modelProviders, SERVE_READY_BUDGET, SERVE_READY_POLL);
    }

    OpenCodeCoreAdapter(OpenCodeProfile profile, RunMcpWiring runMcp,
            Duration serveReadyBudget, Duration serveReadyPoll) {
        this(profile, runMcp, ModelProviderResolver.fallback(), serveReadyBudget, serveReadyPoll);
    }

    OpenCodeCoreAdapter(OpenCodeProfile profile, RunMcpWiring runMcp, ModelProviderResolver modelProviders,
            Duration serveReadyBudget, Duration serveReadyPoll) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.runMcp = Objects.requireNonNull(runMcp, "runMcp");
        this.modelProviders = Objects.requireNonNull(modelProviders, "modelProviders");
        this.serveReadyBudget = Objects.requireNonNull(serveReadyBudget, "serveReadyBudget");
        this.serveReadyPoll = Objects.requireNonNull(serveReadyPoll, "serveReadyPoll");
    }

    /**
     * The model provider a governed launch points opencode at: the operator's
     * active provider, resolved per launch so activating one takes effect on the
     * next run. The run's own model pin stays a session-level record -- the
     * provider block is what actually serves.
     */
    @FunctionalInterface
    public interface ModelProviderResolver {

        ModelProvider resolve();

        /** The documented deepseek fallback used when no provider is configured. */
        static ModelProviderResolver fallback() {
            return ModelProvider::deepseekFallback;
        }
    }

    /**
     * The openai-compatible provider the governed configuration serves with.
     *
     * <p>The provider id, base URL and model are interpolated into the governed
     * JSON document, so each is validated here and a value that could break the
     * document refuses the launch instead of being written.
     *
     * @param providerId the provider key of the document (e.g. {@code deepseek})
     * @param model      the served model id, never carrying a {@code /} (the
     *                   document's {@code model} member is {@code id/model})
     * @param baseUrl    the openai-compatible endpoint
     */
    public record ModelProvider(String providerId, String model, String baseUrl) {

        public ModelProvider {
            providerId = requireJsonMember(providerId, "providerId");
            if (!providerId.matches("[a-z0-9][a-z0-9-]*")) {
                throw new IllegalArgumentException(
                        "The model provider id must be a lowercase [a-z0-9-] token, got: " + providerId);
            }
            model = requireJsonMember(model, "model");
            if (model.contains("/")) {
                throw new IllegalArgumentException(
                        "The model id must not carry a '/', got: " + model);
            }
            baseUrl = requireJsonMember(baseUrl, "baseUrl");
            if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                throw new IllegalArgumentException(
                        "The model provider base URL must be http(s), got: " + baseUrl);
            }
        }

        /**
         * The documented fallback: what the platform has always generated when no
         * provider row is active. The served model may not exist on that endpoint;
         * an active operator provider is the supported configuration.
         */
        public static ModelProvider deepseekFallback() {
            return new ModelProvider("deepseek", "deepseek-chat", "https://api.deepseek.com/v1");
        }

        private static String requireJsonMember(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("The model provider requires a " + name);
            }
            for (char c : value.toCharArray()) {
                if (c == '"' || c == '\\' || c < 0x20) {
                    throw new IllegalArgumentException(
                            "The model provider " + name + " must not carry quotes, backslashes or control"
                                    + " characters, got: " + value);
                }
            }
            return value;
        }
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

    /**
     * The reviewed model pin of every run of this adapter. It is a session-level
     * record: the governed prompt carries no model member, and the served model is
     * the one the resolved {@link ModelProvider} names.
     */
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
        Optional<RunMcpWiring.Endpoint> workerMcp = runMcp.forRun(spec, environment);
        writeGovernedConfiguration(configurationRoot, workerMcp);
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
        // SANDBOX: the run-owned XDG roots must be SANDBOX paths. The governed
        // configuration is uploaded into the sandbox's run control directory
        // (<controlRoot>/<runId>/config, the same tree the launch manifest lives
        // in), so the roots reference that tree; a host path (e.g. D:\... on
        // Windows) is not an absolute path on Linux — opencode resolves it
        // relative to its cwd and silently loses the governed permission policy.
        // HOST: the roots are the run-owned host directories created above.
        if (SandboxBind.isSandboxProxy(environment)) {
            String controlRoot = SandboxLifecycle.DEFAULT_CONTROL_ROOT + "/" + spec.runId();
            serverEnvironment.put("XDG_CONFIG_HOME", controlRoot + "/" + CONFIG_HOME_DIRECTORY);
            serverEnvironment.put("XDG_DATA_HOME", controlRoot + "/" + DATA_HOME_DIRECTORY);
            serverEnvironment.put("XDG_CACHE_HOME", controlRoot + "/" + CACHE_HOME_DIRECTORY);
        } else {
            serverEnvironment.put("XDG_CONFIG_HOME", configHome.toString());
            serverEnvironment.put("XDG_DATA_HOME", dataHome.toString());
            serverEnvironment.put("XDG_CACHE_HOME", cacheHome.toString());
        }
        if (workerMcp.isPresent()) {
            // The run-scoped worker bearer of the platform MCP. The endpoint ignores it
            // in auth-mode=none and requires exactly this kind of token in auth-mode=actor;
            // the wiring never materialises for auth-mode=token.
            serverEnvironment.put("ARIA_MCP_TOKEN", workerMcp.get().token());
        }
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
            // The launch and the session open are separate hops, and the freshly
            // started server needs warm-up before it answers: in SANDBOX mode the
            // execd proxy returns 502 until `serve` listens, and in HOST mode the
            // spawned process takes a moment to bind. openSession never retries a
            // non-2xx (a retried creation could leave a second native session
            // behind), so the open is gated on the health endpoint instead.
            awaitServeReady(client, spec);
            String sessionId = client.openSession(spec.runId().toString());
            return new OpenCodeCoreSession(spec, client, sessionId, profile.model());
        } catch (RuntimeException e) {
            client.close();
            throw e;
        }
    }

    /**
     * Polls the launched server's {@code GET /global/health} until healthy or the
     * wall-clock budget elapses. Budget is enforced on the wall clock because each
     * probe may itself block for the client's HTTP timeout (mirrors the legacy
     * provider's {@code waitForHealth}). The wait is also bounded by the run's
     * frozen deadline, so a launch that starts near expiry cannot sit out the
     * full budget past the point the run must end.
     */
    private void awaitServeReady(OpenCodeHttpClient client, ExecutionSpec spec) {
        long budgetNanos = serveReadyBudget.toNanos();
        if (spec.deadline() != null) {
            long runBudgetNanos = Math.max(0L,
                    Duration.between(Instant.now(), spec.deadline()).toNanos());
            budgetNanos = Math.min(budgetNanos, runBudgetNanos);
        }
        long budgetMillis = budgetNanos / 1_000_000;
        long deadlineNanos = System.nanoTime() + budgetNanos;
        while (System.nanoTime() < deadlineNanos) {
            if (client.isHealthy()) {
                return;
            }
            long remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000;
            if (remainingMillis <= 0) {
                break;
            }
            try {
                Thread.sleep(Math.min(serveReadyPoll.toMillis(), remainingMillis));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                "opencode serve did not become ready within " + budgetMillis
                        + "ms for run " + spec.runId());
    }

    /** The governed configuration file of one run. */
    public static Path governedConfigurationFile(PreparedEnvironment environment) {
        return Path.of(environment.configurationDirectory()).toAbsolutePath().normalize()
                .resolve(CONFIG_HOME_DIRECTORY)
                .resolve("opencode")
                .resolve("opencode.json");
    }

    /** The governed configuration a launch pointing at {@code provider} writes. */
    public static String governedConfigurationJson(ModelProvider provider) {
        Objects.requireNonNull(provider, "provider");
        return governedConfiguration(provider, "");
    }

    /** The governed configuration of the documented fallback provider. */
    public static String governedConfigurationJson() {
        return governedConfigurationJson(ModelProvider.deepseekFallback());
    }

    private static String governedConfiguration(ModelProvider provider, String mcpBlock) {
        return GOVERNED_CONFIGURATION_TEMPLATE.formatted(mcpBlock,
                provider.providerId(), provider.model(),
                provider.providerId(), provider.baseUrl(), provider.model());
    }

    private void writeGovernedConfiguration(Path configurationRoot, Optional<RunMcpWiring.Endpoint> workerMcp) {
        Path target = configurationRoot.resolve(CONFIG_HOME_DIRECTORY).resolve("opencode").resolve("opencode.json");
        ModelProvider provider = modelProviders.resolve();
        String document = workerMcp
                .map(endpoint -> governedConfiguration(provider, platformMcpBlock(endpoint)))
                .orElseGet(() -> governedConfiguration(provider, ""));
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, document, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write the governed OpenCode configuration " + target
                    + ": " + e.getMessage(), e);
        }
    }

    /**
     * The {@code mcp.aria-conductor} block of a wired run: the same remote
     * endpoint shape the legacy provider generated, always carrying the run-scoped
     * bearer (resolved by the server from {@code ARIA_MCP_TOKEN}).
     */
    private static String platformMcpBlock(RunMcpWiring.Endpoint endpoint) {
        // The opencode 1.18 remote-MCP shape: the auth header is a dedicated
        // headers map (a top-level Authorization key is not part of the schema).
        return """
                ,
                  "mcp": {
                    "aria-conductor": {
                      "type": "remote",
                      "url": "%s",
                      "enabled": true,
                      "headers": {
                        "Authorization": "Bearer {env:ARIA_MCP_TOKEN}"
                      }
                    }
                  }""".formatted(endpoint.url());
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
