package io.aria.conductor.app.e2e;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.llm.LlmClient;
import io.aria.conductor.execution.runtime.RuntimeActivity;
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
 *   <li>a harness-scoped {@link RuntimeActivity} view backed by the run store
 *       (see {@link RunStoreQuiescenceView} for the exact contract and the
 *       disclosed limits of that reading);</li>
 *   <li>the validated harness settings ({@code e2e.assets},
 *       {@code e2e.work-root}, {@code e2e.sandbox-transport}; their read-site
 *       defaults -- notably that an unset {@code e2e.sandbox-transport} means
 *       {@code process} -- are documented on {@link #coreE2eSettings}) plus the
 *       bound mock-peer and bridge paths the later harness tasks launch.</li>
 * </ul>
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
    /* RuntimeActivity seam (disclosed substitution)                        */
    /* ------------------------------------------------------------------ */

    /**
     * Harness-scoped quiescence view for preview-first retirement.
     *
     * <p>Production has no {@code RuntimeActivity} bean until Task 18 wires the
     * run coordinator's {@link io.aria.conductor.execution.runtime.RunRuntimeRegistry},
     * and {@code LegacyRetirementService} fails closed without one. The harness
     * must run the production preview/execute endpoints during setup, so it
     * supplies this backing view -- deliberately <em>not</em> a constant that
     * claims quiescence.
     *
     * <p>It reads the run store, the only truthful liveness source in the
     * harness process:
     * <ul>
     *   <li>{@link #activeRuns(UUID)} returns the runs persisted for the agent
     *       in a non-terminal status ({@code PENDING}, {@code INITIALIZING},
     *       {@code RUNNING}, {@code PAUSED} -- the same active set the
     *       retirement service itself applies to persisted rows), so retirement
     *       refuses while a persisted run is live;</li>
     *   <li>{@link #writersStopped(UUID)} is true only when the run exists and
     *       is persisted in a terminal status ({@code COMPLETED}, {@code FAILED},
     *       {@code CANCELLED}, {@code ABORTED}); an unknown run is never called
     *       stopped.</li>
     * </ul>
     *
     * <p>Limit, disclosed rather than papered over: this view cannot observe a
     * runtime this process holds neither of -- and the harness process holds
     * none (no coordinator is wired before Task 18 and the harness never starts
     * a core itself). It is therefore never more permissive than the persisted
     * status reading, and it is replaced wholesale by the production registry
     * when Task 18 wires it. A run whose writers are genuinely live while its
     * persisted status is terminal would not be seen by this view; in this
     * harness no such writer exists.
     */
    static final class RunStoreQuiescenceView implements RuntimeActivity {

        /** Persisted statuses that mean work is still live; mirrors LegacyRetirementService. */
        static final Set<RunStatus> ACTIVE_STATUSES = Set.of(
                RunStatus.PENDING, RunStatus.INITIALIZING, RunStatus.RUNNING, RunStatus.PAUSED);

        /** Persisted statuses that mean no writer of the run can still be live. */
        static final Set<RunStatus> TERMINAL_STATUSES = Set.of(
                RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.ABORTED);

        private final RunRepository runRepository;

        RunStoreQuiescenceView(RunRepository runRepository) {
            this.runRepository = runRepository;
        }

        @Override
        public boolean writersStopped(UUID runId) {
            return runRepository.findById(runId)
                    .map(Run::getStatus)
                    .map(TERMINAL_STATUSES::contains)
                    .orElse(false);
        }

        @Override
        public Set<UUID> activeRuns(UUID agentId) {
            Set<UUID> active = new LinkedHashSet<>();
            for (Run run : runRepository.findByAgentId(agentId)) {
                if (ACTIVE_STATUSES.contains(run.getStatus())) {
                    active.add(run.getId());
                }
            }
            return Set.copyOf(active);
        }
    }

    @Bean
    public RuntimeActivity coreE2eRuntimeActivity(RunRepository runRepository) {
        return new RunStoreQuiescenceView(runRepository);
    }
}
