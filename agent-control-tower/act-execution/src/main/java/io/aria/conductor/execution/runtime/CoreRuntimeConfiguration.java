package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.runtime.AgentExecutionPolicy;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.execution.adk.opencode.OpenCodeProperties;
import io.aria.conductor.execution.approval.PermissionCoordinator;
import io.aria.conductor.execution.credential.RuntimeCredentialService;
import io.aria.conductor.execution.repository.RunExecutionBindingRepository;
import io.aria.conductor.execution.repository.RunWorkspaceLeaseRepository;
import io.aria.conductor.execution.runtime.core.OpenCodeCoreAdapter;
import io.aria.conductor.execution.runtime.core.QoderCoreAdapter;
import io.aria.conductor.execution.runtime.host.HostExecutionBackend;
import io.aria.conductor.execution.runtime.sandbox.SandboxExecutionBackend;
import io.aria.conductor.execution.security.ActorTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Production wiring of the run-owned core runtime (Task 18 cutover): the core
 * catalog and admission policy, the two execution backends, the run coordinator
 * and its {@link RuntimeActivity} view, the core adapters and the launcher the
 * agent loop uses to place a run on its frozen core.
 *
 * <p>The supported production cores are exactly {@code qoder} and
 * {@code opencode} -- the registered adapters, in that order (opencode is the
 * documented default core). There is no LangChain runtime, no Python process
 * and no fallback: an unknown core is refused by admission,
 * {@link CoreAdapters} and the coordinator alike.
 *
 * <p>The coordinator is the production {@link RuntimeActivity}: its
 * {@code writersStopped}/{@code activeRuns} answers come from the in-process
 * runtime registry that observed a verified stop, and preview-first retirement
 * therefore fails closed for any run this process still owns.
 */
@Configuration(proxyBeanMethods = false)
public class CoreRuntimeConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CoreRuntimeConfiguration.class);

    @Bean
    public CoreCatalog coreCatalog() {
        Map<String, Set<ExecutionMode>> modes = new LinkedHashMap<>();
        modes.put("qoder", EnumSet.of(ExecutionMode.HOST, ExecutionMode.SANDBOX));
        modes.put("opencode", EnumSet.of(ExecutionMode.HOST, ExecutionMode.SANDBOX));
        return new CoreCatalog(modes);
    }

    @Bean
    public AgentExecutionPolicy agentExecutionPolicy(CoreCatalog coreCatalog) {
        return new DefaultAgentExecutionPolicy(coreCatalog);
    }

    @Bean
    public RunRuntimeRegistry runRuntimeRegistry() {
        return new RunRuntimeRegistry();
    }

    @Bean
    public RunWorkspaceService runWorkspaceService(RunWorkspaceLeaseRepository leases,
            @Value("${aria.workspaces.runtime-root:./data/workspaces/runs}") String runtimeRoot,
            @Value("${aria.workspaces.result-root:./data/workspaces/results}") String resultRoot) {
        return new RunWorkspaceService(leases, provisionedRoot(runtimeRoot), provisionedRoot(resultRoot));
    }

    /**
     * The service requires existing canonical roots; provisioning them is the
     * wiring's job, so a first boot with an empty data directory succeeds.
     */
    private static Path provisionedRoot(String configured) {
        Path path = Path.of(configured).toAbsolutePath().normalize();
        try {
            return Files.createDirectories(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Workspace root cannot be provisioned: " + path, e);
        }
    }

    @Bean
    public RunFinalizer runFinalizer(WorkspaceService workspaceService) {
        return new RunFinalizer(workspaceService, Clock.systemUTC());
    }

    @Bean
    public ExecutionBackend hostExecutionBackend() {
        return HostExecutionBackend.forCurrentPlatform();
    }

    /**
     * The Sandbox placement backend backed by the pinned OpenSandbox SDK. The
     * per-core image/port map is operator configuration; a core without an entry
     * is refused at preparation instead of being placed with an invented image.
     */
    @Bean
    public SandboxExecutionBackend sandboxExecutionBackend(OpenCodeProperties openCodeProperties,
            @Value("${aria.cores.qoder.sandbox-image:aria-conductor/qoder-sandbox:1.0}") String qoderImage,
            @Value("${aria.cores.qoder.sandbox-port:4096}") int qoderPort) {
        SandboxExecutionBackend.SandboxImages images = coreId -> switch (coreId) {
            case "opencode" -> new SandboxExecutionBackend.SandboxProfile(
                    openCodeProperties.getImage(), openCodeProperties.getPort());
            case "qoder" -> new SandboxExecutionBackend.SandboxProfile(qoderImage, qoderPort);
            default -> null;
        };
        return SandboxExecutionBackend.usingOpenSandbox(openCodeProperties.getSandboxServerUrl(),
                openCodeProperties.getSandboxApiKey(), images);
    }

    @Bean
    public ExecutionBackendRegistry executionBackendRegistry(List<ExecutionBackend> backends) {
        return new ExecutionBackendRegistry(backends);
    }

    /**
     * The run coordinator. It is also the production {@link RuntimeActivity}
     * view (see {@link #runtimeActivity}), so by-type lookups of
     * {@code CoreExecutionService} see two candidate beans - this one and its
     * {@code runtimeActivity} alias, whose instance type is this class. The
     * coordinator is marked {@link Primary} so every by-type resolution
     * (including {@code ObjectProvider#getIfAvailable()} at run time, once both
     * singletons exist) deterministically returns the coordinator instead of
     * raising {@code NoUniqueBeanDefinitionException}.
     */
    @Bean
    @Primary
    public CoreExecutionService coreExecutionService(ExecutionBackendRegistry backends,
            WorkspaceService workspaceService, RunFinalizer runFinalizer,
            RunRuntimeRegistry runRuntimeRegistry, PermissionCoordinator permissions,
            RuntimeCredentialService runtimeCredentials, ActorTokenService actorTokens,
            RunExecutionBindingRepository bindings) {
        return new CoreExecutionService(backends, workspaceService, runFinalizer, runRuntimeRegistry,
                permissions, runtimeCredentials, actorTokens, bindings);
    }

    /**
     * The production quiescence view is the coordinator itself: only observed
     * verified stops and live run-owned runtimes are reported.
     */
    @Bean
    public RuntimeActivity runtimeActivity(CoreExecutionService coreExecutionService) {
        return coreExecutionService;
    }

    @Bean
    public CoreAdapters coreAdapters(List<CoreAdapter> adapters) {
        return new CoreAdapters(adapters);
    }

    @Bean
    public OpenCodeCoreAdapter openCodeCoreAdapter(
            @Value("${aria.cores.opencode.executable:opencode}") String executable,
            @Value("${aria.cores.opencode.version:1.14.31}") String version,
            @Value("${aria.cores.opencode.model:gpt-4o}") String model) {
        return new OpenCodeCoreAdapter(new OpenCodeCoreAdapter.OpenCodeProfile(executable, List.of(),
                Map.of(), version, model));
    }

    @Bean
    public QoderCoreAdapter qoderCoreAdapter(
            @Value("${aria.cores.qoder.node-executable:node}") String nodeExecutable,
            @Value("${aria.cores.qoder.bridge-entry:packages/qoder-acp-bridge/dist/main.js}") String bridgeEntry,
            @Value("${aria.cores.qoder.executable:qoder}") String coreExecutable,
            @Value("${aria.cores.qoder.version:1.1.61}") String version,
            @Value("${aria.cores.qoder.model:gpt-4o}") String model) {
        return new QoderCoreAdapter(new QoderCoreAdapter.QoderProfile(nodeExecutable, bridgeEntry,
                coreExecutable, List.of(), Map.of(), Map.of(), version, model));
    }

    @Bean
    public CoreRunLauncher coreRunLauncher(AgentExecutionPolicy policy, CoreAdapters adapters,
            CoreExecutionService coreExecutionService, RunExecutionBindingRepository bindings,
            TaskDeadlineProperties deadlines) {
        CoreRunLauncher launcher = new CoreRunLauncher(policy, adapters, coreExecutionService,
                bindings, deadlines, Clock.systemUTC());
        log.info("Core run cutover wired: cores={} (no LangChain runtime)", adapters.coreIds());
        return launcher;
    }
}
