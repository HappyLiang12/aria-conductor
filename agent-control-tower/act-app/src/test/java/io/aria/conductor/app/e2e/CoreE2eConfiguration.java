package io.aria.conductor.app.e2e;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Harness configuration of the deterministic core E2E distribution (Task 16).
 *
 * <p>Active only under the {@code core-e2e} profile, so the class can sit on an
 * application classpath without changing anything: the production
 * {@code backend-jar} does not contain it at all, and any other profile ignores
 * it.
 *
 * <p>What it replaces or adds while active:
 * <ul>
 *   <li>both LLM beans -- {@code rawLlmClient} and the primary
 *       {@code resilientLlmClient} -- with one {@link DeterministicLlmClient}
 *       instance, so no code path can reach a model provider. A startup check
 *       verifies the replacement actually won and refuses to run otherwise;</li>
 *   <li>the validated harness settings ({@code e2e.assets},
 *       {@code e2e.work-root}, {@code e2e.sandbox-transport}; their read-site
 *       defaults -- notably that an unset {@code e2e.sandbox-transport} means
 *       {@code process} -- are documented on {@link #coreE2eSettings}) plus the
 *       bound mock-peer and bridge paths the later harness tasks launch.</li>
 * </ul>
 *
 * <p>The Task 16 store-backed {@code RuntimeActivity} bean is gone: the Task 18
 * cutover wires the production run coordinator as the process's quiescence view,
 * so the harness consumes the production bean instead of a second one.
 */
@Configuration(proxyBeanMethods = false)
@Profile("core-e2e")
public class CoreE2eConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CoreE2eConfiguration.class);

    /** Mock protocol executables every distribution must carry. */
    static final List<String> REQUIRED_PEER_ASSETS =
            List.of("peers/mock-qoder.mjs", "peers/mock-opencode.mjs", "peers/peer-actions.mjs");

    /** Scenario manifest the peers load from {@code peers/../scenarios.json}. */
    static final String SCENARIO_MANIFEST = "scenarios.json";

    /** Built Qoder ACP bridge entry point. */
    static final String BRIDGE_ENTRY = "bridge/main.js";

    /** How the harness runs SANDBOX-placed runs: a local process, or a real sandbox. */
    public enum SandboxTransport {
        PROCESS,
        REAL
    }

    /**
     * The harness runtime contract: where the extracted distribution lives,
     * where harness-owned workspaces are created, and which sandbox transport
     * the run path must use.
     */
    public record CoreE2eSettings(Path assetsRoot, Path workRoot, SandboxTransport sandboxTransport,
            Path qoderPeerScript, Path opencodePeerScript, Path scenarioManifest, Path bridgeEntry) {
    }

    /**
     * The one place the harness settings are read. Defaults are part of the
     * runtime contract and stated here, next to the read:
     * <ul>
     *   <li>{@code e2e.assets} is <b>required</b>: there is no default and
     *       startup refuses without it;</li>
     *   <li>{@code e2e.work-root} unset resolves to
     *       {@code <java.io.tmpdir>/aria-core-e2e-work} (created if missing);</li>
     *   <li>{@code e2e.sandbox-transport} unset resolves to {@code process}, the
     *       local-process transport -- unset never selects the real sandbox, and
     *       a value that is set must be exactly {@code process} or {@code real}
     *       (anything else refuses startup).</li>
     * </ul>
     */
    @Bean
    public CoreE2eSettings coreE2eSettings(
            @Value("${e2e.assets:}") String assets,
            @Value("${e2e.work-root:}") String workRoot,
            @Value("${e2e.sandbox-transport:process}") String sandboxTransport) throws IOException {
        if (assets == null || assets.isBlank()) {
            throw new IllegalStateException("The core E2E harness requires --e2e.assets=<extracted distribution root>;"
                    + " the mock peers and the built Qoder ACP bridge are resolved from that tree"
                    + " and are never read from the classpath");
        }
        Path assetsRoot = Path.of(assets).toAbsolutePath().normalize();
        if (!Files.isDirectory(assetsRoot)) {
            throw new IllegalStateException("e2e.assets is not a directory: " + assetsRoot);
        }
        List<String> missing = new ArrayList<>();
        List<String> required = new ArrayList<>(REQUIRED_PEER_ASSETS);
        required.add(SCENARIO_MANIFEST);
        required.add(BRIDGE_ENTRY);
        for (String relative : required) {
            if (!Files.isRegularFile(assetsRoot.resolve(relative))) {
                missing.add(relative);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("The extracted core E2E distribution is incomplete under "
                    + assetsRoot + "; missing: " + missing);
        }

        Path resolvedWorkRoot = workRoot == null || workRoot.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "aria-core-e2e-work")
                : Path.of(workRoot);
        resolvedWorkRoot = resolvedWorkRoot.toAbsolutePath().normalize();
        Files.createDirectories(resolvedWorkRoot);

        SandboxTransport transport = parseSandboxTransport(sandboxTransport);

        CoreE2eSettings settings = new CoreE2eSettings(assetsRoot, resolvedWorkRoot, transport,
                assetsRoot.resolve("peers/mock-qoder.mjs"),
                assetsRoot.resolve("peers/mock-opencode.mjs"),
                assetsRoot.resolve(SCENARIO_MANIFEST),
                assetsRoot.resolve(BRIDGE_ENTRY));
        log.info("core-e2e harness: assets={} workRoot={} sandboxTransport={} peers=[{}, {}] bridge={}",
                settings.assetsRoot(), settings.workRoot(),
                settings.sandboxTransport().name().toLowerCase(Locale.ROOT),
                settings.qoderPeerScript().getFileName(), settings.opencodePeerScript().getFileName(),
                settings.bridgeEntry());
        return settings;
    }

    /**
     * Exactly {@code process} or {@code real}; anything else refuses startup.
     * An unset property is resolved to {@code process} by the default at the
     * read site (see {@link #coreE2eSettings}).
     */
    static SandboxTransport parseSandboxTransport(String value) {
        String normalized = value == null ? "" : value.trim();
        return switch (normalized) {
            case "process" -> SandboxTransport.PROCESS;
            case "real" -> SandboxTransport.REAL;
            default -> throw new IllegalArgumentException("e2e.sandbox-transport must be exactly"
                    + " 'process' or 'real' but was: '" + value + "'");
        };
    }

    /* ------------------------------------------------------------------ */
    /* Deterministic LLM wiring                                             */
    /* ------------------------------------------------------------------ */

    @Bean
    public DeterministicLlmClient deterministicLlmClient() {
        return new DeterministicLlmClient();
    }

    /**
     * Replaces the production {@code rawLlmClient} bean (normally the HTTP
     * {@code DefaultLlmClient}) with the deterministic client.
     */
    @Bean("rawLlmClient")
    public LlmClient rawLlmClient(DeterministicLlmClient deterministicLlmClient) {
        return deterministicLlmClient;
    }

    /**
     * Replaces the production primary {@code resilientLlmClient} bean (normally
     * the retry decorator around the HTTP client) with the same deterministic
     * instance, and stays {@link Primary} so by-type injection keeps resolving
     * without ambiguity.
     */
    @Bean("resilientLlmClient")
    @Primary
    public LlmClient resilientLlmClient(DeterministicLlmClient deterministicLlmClient) {
        return deterministicLlmClient;
    }

    /**
     * Refuses to run unless the replacement actually won: injecting by name and
     * by type must both yield the deterministic instance. A silent fallback to
     * the production HTTP client would turn the harness into live traffic.
     */
    @Bean
    public ApplicationRunner coreE2eWiringCheck(ConfigurableApplicationContext context,
            DeterministicLlmClient deterministicLlmClient, CoreE2eSettings settings) {
        return args -> {
            Object raw = context.getBeanFactory().getBean("rawLlmClient");
            Object resilient = context.getBeanFactory().getBean("resilientLlmClient");
            if (raw != deterministicLlmClient || resilient != deterministicLlmClient
                    || context.getBean(LlmClient.class) != deterministicLlmClient) {
                throw new IllegalStateException("The core E2E harness refused to start: LlmClient beans"
                        + " were not replaced by the deterministic client (rawLlmClient="
                        + raw.getClass().getName() + ", resilientLlmClient="
                        + resilient.getClass().getName() + ")");
            }
            log.info("core-e2e harness wiring verified: rawLlmClient and resilientLlmClient are the"
                            + " deterministic client; llm calls without an exact scenario response fail"
                            + " loudly; e2e.sandbox-transport={}", settings.sandboxTransport());
        };
    }

    /* ------------------------------------------------------------------ */
    /* Task 19 peer-launch wiring                                          */
    /* ------------------------------------------------------------------ */

    /*
     * The harness control surface ({@link CoreE2eController}: the peer-scenario
     * selection route and the run-worker-credential route the Playwright
     * fixtures call) is NOT registered here: it is a {@code @RestController} on
     * the test classpath and carries {@code @Profile("core-e2e")} itself, so the
     * application component scan registers it in exactly this context. An extra
     * @Bean method would duplicate the scanned definition under the same name.
     */

    /**
     * The harness peer-scenario registry. It validates every selection against
     * the shipped manifest and requires the peer control token the mock peers
     * themselves require, so a harness run can never reach a fixture-less peer.
     */
    @Bean
    public CoreE2eScenarios coreE2eScenarios(CoreE2eSettings settings,
            @Value("${" + CoreE2eScenarios.PEER_CONTROL_TOKEN_ENV + ":}") String peerControlToken) {
        return new CoreE2eScenarios(settings.scenarioManifest(), peerControlToken);
    }

    /** The run-scoped worker credential custody: minted once per run, shared by the route and the launch. */
    @Bean
    public CoreE2eWorkerTokens coreE2eWorkerTokens(
            io.aria.conductor.execution.security.ActorTokenService actorTokens) {
        return new CoreE2eWorkerTokens(actorTokens);
    }

    /**
     * The shared run-owned state of the harness process transport, plus the two
     * backend views (one per placement mode) the registry consumes.
     */
    @Bean(destroyMethod = "close")
    public CoreE2eProcessBackend.State coreE2ePeerProcesses(CoreE2eWorkerTokens workerTokens) {
        return new CoreE2eProcessBackend.State(
                io.aria.conductor.execution.runtime.host.OwnedProcessController.forCurrentPlatform(),
                workerTokens);
    }

    @Bean
    public CoreE2eProcessBackend coreE2eHostBackend(CoreE2eProcessBackend.State state) {
        return new CoreE2eProcessBackend(io.aria.conductor.common.runtime.ExecutionMode.HOST, state);
    }

    @Bean
    public CoreE2eProcessBackend coreE2eSandboxBackend(CoreE2eProcessBackend.State state) {
        return new CoreE2eProcessBackend(io.aria.conductor.common.runtime.ExecutionMode.SANDBOX, state);
    }

    /** The harness OpenCode core: the committed mock peer behind the production adapter. */
    @Bean
    public io.aria.conductor.execution.runtime.CoreAdapter coreE2eOpenCodeAdapter(
            CoreE2eScenarios scenarios, CoreE2eProcessBackend.State peers, CoreE2eSettings settings,
            @Value("${e2e.node-executable:node}") String nodeExecutable) {
        return new CoreE2eOpenCodeAdapter(scenarios, peers, nodeExecutable, settings.opencodePeerScript());
    }

    /**
     * The harness Qoder core: the real committed ACP bridge driving the committed
     * mock CLI behind the production adapter. The configured node executable is
     * resolved to the absolute existing file the bridge requires for
     * {@code --cli} ({@link CoreE2eQoderAdapter#resolveAbsoluteCoreExecutable}):
     * the committed bridge refuses a bare name with {@code E_CONFIG} before it
     * binds its endpoint, so the resolution -- and a loud refusal when the name
     * has no absolute answer -- happens here, at boot, never at launch time.
     */
    @Bean
    public io.aria.conductor.execution.runtime.CoreAdapter coreE2eQoderAdapter(
            CoreE2eScenarios scenarios, CoreE2eProcessBackend.State peers, CoreE2eSettings settings,
            @Value("${e2e.node-executable:node}") String nodeExecutable) {
        String coreExecutable = CoreE2eQoderAdapter.resolveAbsoluteCoreExecutable(nodeExecutable);
        log.info("core-e2e harness: the Qoder bridge core executable '{}' resolved to '{}' (the bridge"
                + " accepts only an absolute existing --cli)", nodeExecutable, coreExecutable);
        return new CoreE2eQoderAdapter(scenarios, peers, nodeExecutable, coreExecutable,
                settings.bridgeEntry(), settings.qoderPeerScript());
    }

    /**
     * Registers the built-in agents' default scenarios: {@value CoreE2eScenarios#DEFAULT_SCENARIO}
     * everywhere except the SDD QA built-in, which boots on
     * {@value CoreE2eScenarios#QA_SCENARIO} (the chain routers the QA step on
     * the {@code VERDICT=} marker its run's output must carry).
     *
     * <p>Aria's conversation spec drives the chat route and a workflow chain
     * resolves its steps by agent role, so neither lane can select a scenario
     * through the control route for the agent the run will actually use: Aria's
     * runs and the SDD role-agent steps would otherwise fail closed at the
     * scenario gate. The registered ids are exactly the built-ins the harness
     * setup ({@code initialize-builtins}) guarantees exist with the production
     * default selection -- Aria and the SDD BA/DEV/QA rows of
     * {@code V42__seed_sdd_role_agents.sql}, which on a migrated harness schema
     * already carry the supported core -- so a spec-created agent still
     * carries an explicit selection and an explicit selection always wins.
     */
    @Bean
    public ApplicationRunner coreE2eScenarioDefaults(CoreE2eScenarios scenarios) {
        return args -> {
            scenarios.registerDefault(io.aria.conductor.common.AriaConstants.ARIA_AGENT_ID,
                    CoreE2eScenarios.DEFAULT_SCENARIO);
            List<UUID> builtinAgentIds = new ArrayList<>();
            for (io.aria.conductor.execution.maintenance.LegacySetupService.BuiltinAgent builtin
                    : io.aria.conductor.execution.maintenance.LegacySetupService.BUILTIN_AGENTS) {
                scenarios.registerDefault(builtin.id(),
                        "qa".equals(builtin.role())
                                ? CoreE2eScenarios.QA_SCENARIO
                                : CoreE2eScenarios.DEFAULT_SCENARIO);
                builtinAgentIds.add(builtin.id());
            }
            log.info("core-e2e peer scenarios: {} declared in the manifest; the harness defaults ('{}'"
                            + " + QA '{}') are registered for the built-in agents {}",
                    scenarios.knownScenarios().size(),
                    CoreE2eScenarios.DEFAULT_SCENARIO, CoreE2eScenarios.QA_SCENARIO, builtinAgentIds);
        };
    }

    /**
     * Replaces the production placement backends and core adapters with the
     * harness ones: the mock peers are launched by the harness process transport
     * (both modes) and served through the harness adapters. The replacement is
     * explicit by bean name -- the harness profile must never run the production
     * sandbox backend (no container runtime) nor a real core binary. The
     * production {@code gitBranchService} is replaced by its harness twin below.
     */
    @Bean
    public static org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
            coreE2eRuntimeOverrides() {
        return registry -> {
            for (String bean : List.of("hostExecutionBackend", "sandboxExecutionBackend",
                    "openCodeCoreAdapter", "qoderCoreAdapter", "gitBranchService")) {
                if (registry.containsBeanDefinition(bean)) {
                    registry.removeBeanDefinition(bean);
                    log.info("core-e2e harness replaced the production bean '{}'", bean);
                }
            }
        };
    }

    /**
     * The SDD branch handoff of the harness: the production service resolves the
     * GitHub credential from the git pack or the host {@code GITHUB_TOKEN}. The
     * required PR E2E must run without live credentials, so the harness supplies
     * the synthetic handoff credential the Task 17 recipe used
     * ({@code synthetic-e2e-git-handoff}) when the environment provides none; a
     * real environment token always wins. The service is the production class
     * unchanged -- only its constructor arguments differ: the credential, and the
     * API base pointed at {@link CoreE2eGitHubMock} so the branch/commit handoff
     * of a SPEC_REVIEW approval is exercised end to end without reaching
     * github.com.
     */
    @Bean
    public io.aria.conductor.execution.git.GitBranchService coreE2eGitBranchService(CoreE2eGitHubMock gitHubMock) {
        String environmentToken = System.getenv("GITHUB_TOKEN");
        if (environmentToken != null && !environmentToken.isBlank()) {
            log.info("core-e2e harness: GitBranchService uses the environment GITHUB_TOKEN against the local git mock");
            return new io.aria.conductor.execution.git.GitBranchService(environmentToken, gitHubMock.apiBaseUrl());
        }
        log.info("core-e2e harness: GitBranchService uses the synthetic SDD handoff credential"
                + " against the local git mock (no GITHUB_TOKEN in the environment)");
        return new io.aria.conductor.execution.git.GitBranchService(SYNTHETIC_GIT_HANDOFF_TOKEN,
                gitHubMock.apiBaseUrl());
    }

    /**
     * The synthetic SDD handoff credential of the harness (Task 17 round-2
     * recipe); it satisfies the TP1 availability gate and is never a real token.
     */
    static final String SYNTHETIC_GIT_HANDOFF_TOKEN = "synthetic-e2e-git-handoff";

    /* ------------------------------------------------------------------ */
    /* RuntimeActivity                                                       */
    /* ------------------------------------------------------------------ */

    /*
     * The harness no longer supplies a RuntimeActivity bean (Task 18): the
     * production cutover wires the run coordinator as the process's quiescence
     * view, so supplying a second bean here would collide with it. The previous
     * store-backed view (RunStoreQuiescenceView, T16) was merged into -- and
     * removed in favour of -- the production coordinator, whose answers come
     * from observed verified stops rather than persisted statuses alone.
     */
}
