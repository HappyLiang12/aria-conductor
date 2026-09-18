package io.aria.conductor.execution.adk.qoder;

import io.aria.conductor.common.event.RunProgressEvent;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.execution.adk.AbstractAdkProvider;
import io.aria.conductor.execution.adk.TaskContext;
import io.aria.conductor.execution.adk.TaskExecutionConstraints;
import io.aria.conductor.execution.adk.TaskExecutionException;
import io.aria.conductor.execution.adk.TaskResult;
import io.aria.conductor.execution.approval.AcpPermissionCoordinator;
import io.aria.conductor.execution.approval.RunScopedCredentialService;
import io.aria.conductor.execution.approval.WriteGrantService;
import io.aria.conductor.execution.credential.RuntimeCredentialException;
import io.aria.conductor.execution.credential.RuntimeCredentialService;
import io.aria.conductor.execution.llm.LlmMessage;
import io.aria.conductor.execution.llm.LlmResponse;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.execution.mcp.SandboxHostResolver;
import io.aria.conductor.execution.sandbox.SandboxLifecycle;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Qoder agent provider — one Qoder CLI bridge per agent, running inside an
 * OpenSandbox sandbox, driven over the frozen C0.2 HTTP/SSE bridge contract
 * ({@link QoderBridgeClient}).
 *
 * <p>Qoder is an end-to-end agent (task-level semantics): {@link #supportsTaskExecution()}
 * is {@code true} and runs are driven through {@link #executeTask}; the turn-level
 * {@link #call} throws {@link UnsupportedOperationException}.
 *
 * <p>Isolation model: one sandbox per agent (created in {@link #prepareAgent}, reused
 * across runs, killed by {@link #shutdownAgent}/{@link #shutdownAll}), one bridge
 * session per run. A second concurrent run for the same agent is rejected with a typed
 * provider error instead of being interleaved into the first run's session.
 *
 * <p>Credential model: the Qoder PAT is read from {@link RuntimeCredentialService} at
 * sandbox preparation and delivered ONLY through the sandbox container environment
 * ({@code QODER_PERSONAL_ACCESS_TOKEN}); the bridge requires a non-empty
 * {@code BRIDGE_TOKEN} (fail closed), so every sandbox gets a fresh random token that
 * is handed to {@link QoderBridgeClient} as its bearer value. Neither value is ever
 * logged or written to a file.
 *
 * <p>Worker MCP entry (R11, C4): when MCP is enabled the run's {@code session/new} carries one
 * {@code mcpServers} entry named {@code aria} — the host endpoint the sandbox itself proved it
 * can reach — authenticated with a run-scoped worker credential instead of the platform token.
 * The credential and every one-use write grant of the run are revoked when the run ends.
 *
 * <p>Terminal semantics: a run finishes only on an explicit {@code completed}/{@code failed}
 * bridge event. A clean server-side end of the event stream without a terminal event is
 * NOT a completion — it is reported as a typed provider failure.
 *
 * <p>Deadline model (C0.6): the caller-provided {@link TaskContext#maxDuration()} is
 * honored; a {@code null} duration falls back to {@code qoder.max-task-minutes}. On
 * timeout the bridge session is cancelled first and the agent's sandbox is killed only
 * as the last bounded fallback when the stop cannot be proven.
 */
@Slf4j
@Component
public class QoderAdkProvider extends AbstractAdkProvider {

    /** The command that starts the in-sandbox bridge when the image CMD did not. */
    public static final String BRIDGE_START_COMMAND = "node /opt/qoder/bridge/dist/main.js";

    private static final String PROVIDER_ID = "qoder";
    /** Fixed working directory inside the sandbox (Dockerfile WORKDIR). */
    private static final String SANDBOX_CWD = "/workspace";
    private static final String PAT_ENV = "QODER_PERSONAL_ACCESS_TOKEN";
    private static final String BRIDGE_TOKEN_ENV = "BRIDGE_TOKEN";
    private static final String PORT_ENV = "PORT";
    /** Max time to wait for the bridge {@code /health} to answer after sandbox creation. */
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(60);
    /** Health poll interval while waiting for bridge readiness. */
    private static final Duration READY_POLL_INTERVAL = Duration.ofMillis(500);
    /** Execd-readiness probe interval (the exec channel lags sandbox creation: skipHealthCheck). */
    private static final long EXECD_READY_POLL_MS = 500L;
    /** Execd-readiness budget — a cold first boot of the sandbox image needs several seconds. */
    private static final Duration EXECD_READY_TIMEOUT = Duration.ofSeconds(90);
    /** Re-issue the bridge start command at most this often while bridge readiness is unproven. */
    private static final Duration BRIDGE_START_RETRY_INTERVAL = Duration.ofSeconds(10);
    /** TTL extension requested on each renewal during a long task (matches the sandbox TTL). */
    private static final Duration RENEW_EXTENSION = Duration.ofMinutes(30);
    /** Bounded wait for a cancelled run's event stream to stop before killing the sandbox. */
    private static final Duration STOP_GRACE = Duration.ofSeconds(15);
    /** Entropy of the per-sandbox bridge bearer token (256-bit random, never derived from the PAT). */
    private static final int BRIDGE_TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final QoderProperties properties;
    private final SandboxLifecycle sandboxLifecycle;
    private final RuntimeCredentialService credentialService;
    /** S10: optional publisher for run.progress events (null = pump silent). */
    private final ApplicationEventPublisher eventPublisher;
    private final BiFunction<String, String, QoderBridgeClient> clientFactory;
    /** True only when this provider created (and therefore must close) its bridge clients. */
    private final boolean ownsClients;
    /**
     * {@code aria.mcp.*} — the worker MCP entry is registered only when MCP is enabled AND the
     * credential/grant/coordinator collaborators exist; null (manual wirings) keeps it off.
     */
    private final McpProperties mcpProperties;
    /** Issues the run-scoped worker credential the MCP entry authenticates with (R11). */
    private final RunScopedCredentialService runScopedCredentialService;
    /** One-use write grants bound to a run — revoked when the run ends (C4). */
    private final WriteGrantService writeGrantService;
    /** Host-side ACP permission coordinator (C2); null keeps the pump's raw-ask sink silent. */
    private final AcpPermissionCoordinator permissionCoordinator;
    /**
     * Source of sandbox-reachable host candidates for the MCP probe (R11). Overridable in
     * tests; the injected override ({@code aria.mcp.sandbox-host-address}) wins inside.
     */
    private Function<String, SandboxHostResolver> hostResolverFactory = SandboxHostResolver::fromSystemInterfaces;

    private final Map<UUID, QoderInstance> instances = new ConcurrentHashMap<>();
    private final Map<UUID, String> runSessions = new ConcurrentHashMap<>();
    private final Map<UUID, QoderBridgeClient> runClients = new ConcurrentHashMap<>();
    private final Map<UUID, QoderInstance> runInstances = new ConcurrentHashMap<>();
    private final Map<UUID, QoderProgressPump> runPumps = new ConcurrentHashMap<>();
    /** Pending aborts recorded while a run's session was not yet created (see {@link #abortTask}). */
    private final Map<UUID, Boolean> runAborted = new ConcurrentHashMap<>();
    /** Runs registered for cancellation before sandbox preparation (F5); consulted by {@link #abortTask}. */
    private final Map<UUID, Boolean> registeredRuns = new ConcurrentHashMap<>();
    /** agentId → runId of the run currently in flight (one run per agent, busy rejection). */
    private final Map<UUID, UUID> activeRuns = new ConcurrentHashMap<>();
    /** In-flight sandbox preparations, keyed by agentId — concurrent callers share one prepare. */
    private final Map<UUID, CompletableFuture<QoderInstance>> preparing = new ConcurrentHashMap<>();
    /** Executor for sandbox preparation (sleep-based health polling must not block common pool). */
    private final ExecutorService prepareExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /** Ready-wait budget (overridable in tests). */
    private Duration bridgeReadyTimeout = READY_TIMEOUT;
    /** Health poll interval while waiting for readiness (overridable in tests). */
    private Duration bridgeReadyPollInterval = READY_POLL_INTERVAL;
    /** Bridge-start re-issue interval while readiness is unproven (overridable in tests). */
    private Duration bridgeStartRetryInterval = BRIDGE_START_RETRY_INTERVAL;
    /** Cancel-to-kill grace (overridable in tests). */
    private Duration stopGrace = STOP_GRACE;

    /** Spring constructor — creates the shared sandbox lifecycle from the qoder properties. */
    @Autowired
    public QoderAdkProvider(QoderProperties properties, RuntimeCredentialService credentialService,
                            ApplicationEventPublisher eventPublisher, McpProperties mcpProperties,
                            RunScopedCredentialService runScopedCredentialService,
                            WriteGrantService writeGrantService,
                            AcpPermissionCoordinator permissionCoordinator) {
        this(properties, new SandboxLifecycle(properties.getSandboxServerUrl(), properties.getSandboxApiKey()),
                credentialService, eventPublisher, null, mcpProperties, runScopedCredentialService,
                writeGrantService, permissionCoordinator);
    }

    /**
     * Manual-wiring constructor (used by the sandbox smoke test): the caller supplies the
     * fully built {@link SandboxLifecycle}; bridge clients are created and owned by the provider.
     * Without Spring collaborators the worker MCP entry stays off (no credential source).
     */
    public QoderAdkProvider(QoderProperties properties, SandboxLifecycle sandboxLifecycle,
                            RuntimeCredentialService credentialService, ApplicationEventPublisher eventPublisher) {
        this(properties, sandboxLifecycle, credentialService, eventPublisher, null, null, null, null, null);
    }

    /**
     * Minimal-wiring constructor (sandbox smoke test): the shared {@link SandboxLifecycle} is
     * built from the qoder properties, and the absent MCP/approval collaborators keep the
     * worker MCP entry and the ACP permission sink off.
     */
    public QoderAdkProvider(QoderProperties properties, RuntimeCredentialService credentialService,
                            ApplicationEventPublisher eventPublisher) {
        this(properties, new SandboxLifecycle(properties.getSandboxServerUrl(), properties.getSandboxApiKey()),
                credentialService, eventPublisher, null, null, null, null, null);
    }

    /**
     * Test constructor: a non-null {@code clientFactory} replaces client creation (the
     * returned clients are owned by the caller and never closed by the provider).
     */
    QoderAdkProvider(QoderProperties properties, SandboxLifecycle sandboxLifecycle,
                     RuntimeCredentialService credentialService, ApplicationEventPublisher eventPublisher,
                     BiFunction<String, String, QoderBridgeClient> clientFactory,
                     McpProperties mcpProperties,
                     RunScopedCredentialService runScopedCredentialService,
                     WriteGrantService writeGrantService,
                     AcpPermissionCoordinator permissionCoordinator) {
        this.properties = properties;
        this.sandboxLifecycle = sandboxLifecycle;
        this.credentialService = credentialService;
        this.eventPublisher = eventPublisher;
        this.clientFactory = clientFactory != null ? clientFactory : QoderBridgeClient::new;
        this.ownsClients = clientFactory == null;
        this.mcpProperties = mcpProperties;
        this.runScopedCredentialService = runScopedCredentialService;
        this.writeGrantService = writeGrantService;
        this.permissionCoordinator = permissionCoordinator;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean supportsTaskExecution() {
        return true;
    }

    /** The Qoder task deadline ({@code qoder.max-task-minutes}) resolved through the provider (C0.6). */
    @Override
    public TaskExecutionConstraints taskConstraints() {
        return new TaskExecutionConstraints(Duration.ofMinutes(properties.getMaxTaskMinutes()));
    }

    @Override
    public void prepareAgent(UUID agentId, Agent agent) {
        requireGovernedMcpConfiguration();
        getOrPrepareInstance(agentId, agent);
    }

    @Override
    public LlmResponse call(UUID agentId, List<LlmMessage> messages, List<Map<String, Object>> tools) {
        throw new UnsupportedOperationException(
                "Qoder provider does not support turn-level call semantics; use executeTask(...)");
    }

    @Override
    public TaskResult executeTask(Agent agent, UUID runId, String taskPrompt, TaskContext context) {
        if (agent == null || agent.getId() == null || runId == null) {
            throw new TaskExecutionException(TaskExecutionException.Cause.PROVIDER_ERROR,
                    "agent and runId must not be null");
        }
        requireGovernedMcpConfiguration();
        UUID agentId = agent.getId();
        // One run per agent: a second concurrent run would race the first run's single
        // bridge session, so it is rejected with a typed error instead of interleaving.
        UUID previous = activeRuns.putIfAbsent(agentId, runId);
        if (previous != null) {
            throw new TaskExecutionException(TaskExecutionException.Cause.PROVIDER_ERROR,
                    "Agent " + agentId + " already has an active run (" + previous + ")"
                            + " — Qoder allows one run per agent at a time");
        }
        try {
            return runTask(agent, agentId, runId, taskPrompt, context);
        } finally {
            activeRuns.remove(agentId, runId);
        }
    }

    private TaskResult runTask(Agent agent, UUID agentId, UUID runId, String taskPrompt, TaskContext context) {
        // C0.6: the caller's window covers the whole call — resolve the deadline before
        // sandbox prep so the (up to ~150s) preparation time cannot escape it. The same
        // absolute deadline is handed to the coordinator and to the run-scoped credential:
        // their windows must derive from the host's grant, not restart after preparation.
        Duration deadline = resolveMaxDuration(context);
        long deadlineNanos = System.nanoTime() + deadline.toNanos();
        Instant runDeadline = Instant.now().plus(deadline);
        // F5: register the run for cancellation BEFORE preparation starts, so an abort that
        // lands while the sandbox is still being prepared is recorded (pending abort) and
        // honoured at the next launch boundary instead of being dropped. The try starts
        // right after the registration so its finally covers EVERY exit path (preparation
        // failure, createSession failure, the pre-execution abort throw, the normal end) —
        // no registration can leak.
        registeredRuns.put(runId, Boolean.TRUE);
        QoderInstance inst = null;
        QoderBridgeClient client = null;
        String sessionId = null;
        QoderProgressPump pump = null;
        ScheduledExecutorService renewExecutor = null;
        boolean coordinatorBound = false;
        try {
            inst = getOrPrepareInstance(agentId, agent);
            // F5: an abort recorded during preparation terminates the run here, before a
            // session exists — a cancelled run never creates a session and never prompts.
            if (runAborted.remove(runId) != null) {
                throw new TaskExecutionException(TaskExecutionException.Cause.ABORTED,
                        "Run " + runId + " was cancelled during sandbox preparation");
            }
            client = inst.client();
            // Register the client before createSession so a cancel landing in the
            // session-creation window is recorded (pending abort) and honored immediately
            // after the session exists.
            runClients.put(runId, client);
            runInstances.put(runId, inst);
            // R11: the worker MCP entry is registered only after the sandbox itself proved it can
            // reach the host (probe from inside, never from here) and it authenticates with a
            // run-scoped credential, so the sandbox never sees a long-lived platform token.
            String workerToken = null;
            List<QoderBridgeClient.McpServer> mcpServers = List.of();
            if (workerMcpEnabled()) {
                workerToken = runScopedCredentialService.issue(runId, runDeadline);
                mcpServers = workerMcpServers(client, runId, workerToken);
            } else if (mcpProperties != null && mcpProperties.isEnabled()) {
                log.warn("Qoder run {}: MCP is enabled but the worker entry cannot be wired"
                                + " (port={}, credential service present={}) — the run proceeds without MCP tools",
                        runId, mcpProperties.getPort(), runScopedCredentialService != null);
            }
            if (permissionCoordinator != null) {
                permissionCoordinator.bindRun(runId, client, runDeadline);
                coordinatorBound = true;
            }
            try {
                sessionId = client.createSession(new QoderBridgeClient.CreateSessionRequest(
                        "run-" + runId, agentId.toString(), SANDBOX_CWD, properties.getModel(),
                        deadlineSeconds(deadline), mcpServers));
            } catch (QoderBridgeException e) {
                throw mapBridgeFailure(runId, e);
            }
            runSessions.put(runId, sessionId);
            // A cancel that arrived before the session existed must terminate the run
            // right away rather than letting it execute in the sandbox.
            if (runAborted.remove(runId) != null) {
                log.info("Qoder run {} cancelled before execution started — cancelling session {}", runId, sessionId);
                throw new TaskExecutionException(TaskExecutionException.Cause.ABORTED,
                        "Run " + runId + " cancelled before execution started");
            }
            log.info("Qoder task {} started for agent {} (session {})", runId, agentId, sessionId);

            // The raw-ask sink is the only path by which a permission_request reaches the host's
            // ACP coordinator: the mapped progress events cannot carry the ask. A null coordinator
            // leaves the sink null (silent), mirroring the manual wirings.
            String bridgeSession = sessionId;
            pump = new QoderProgressPump(client, bridgeSession, runId, agentId, this::publishProgress,
                    permissionCoordinator == null ? null
                            : ask -> permissionCoordinator.handlePermissionEvent(
                                    runId, agentId, bridgeSession, ask.payload()));
            runPumps.put(runId, pump);
            pump.start();
            // Renew the sandbox TTL while the long-lived run blocks — the SDK's own heartbeat
            // fires too late at the 30-minute TTL boundary (R3-F2).
            renewExecutor = startRenewHeartbeat(inst);
            try {
                try {
                    client.prompt(sessionId, taskPrompt);
                } catch (QoderBridgeException e) {
                    throw mapBridgeFailure(runId, e);
                }
                Duration remaining = Duration.ofNanos(Math.max(1L, deadlineNanos - System.nanoTime()));
                QoderProgressPump.WaitResult wait = pump.awaitTerminal(remaining);
                return switch (wait.status()) {
                    case TERMINAL -> terminalResult(runId, agentId, sessionId, wait.outcome());
                    case TIMEOUT -> {
                        log.warn("Qoder task {} exceeded its deadline of {}s for agent {} — cancelling session {}",
                                runId, deadline.toSeconds(), agentId, sessionId);
                        stopRun(runId, sessionId, client, inst);
                        throw new TaskExecutionException(TaskExecutionException.Cause.TIMEOUT,
                                "Qoder run " + runId + " exceeded its task deadline of " + deadline.toSeconds() + "s");
                    }
                    case STREAM_ENDED -> {
                        if (runAborted.remove(runId) != null) {
                            throw new TaskExecutionException(TaskExecutionException.Cause.ABORTED,
                                    "Qoder run " + runId + " was cancelled");
                        }
                        // A clean server-side EOF is not a completion: only an explicit
                        // completed/failed event may end a run.
                        String detail = pump.streamFailureMessage();
                        throw new TaskExecutionException(TaskExecutionException.Cause.PROVIDER_ERROR,
                                "Qoder run " + runId + " event stream ended without an explicit terminal event"
                                        + (detail == null ? "" : ": " + detail));
                    }
                };
            } finally {
                if (renewExecutor != null) {
                    renewExecutor.shutdownNow();
                }
                if (pump != null) {
                    pump.stop();
                    runPumps.remove(runId);
                }
            }
        } finally {
            // F5: the cancellation registration dies with the run on every exit path.
            registeredRuns.remove(runId);
            // Design §3.1: the bridge keeps a session (and its qodercli child) alive after
            // prompt_result, so the run's end must terminate it — otherwise every finished
            // run leaks one session + child into the agent's sandbox. The bridge's cancel
            // is idempotent: an already-ended session answers 202 {terminated:false} without
            // side effects, while a completed-but-open session rejects pending decisions,
            // sends the ACP cancel and terminates the child. The stop-grace/kill sequence
            // stays in stopRun (abort/timeout paths only).
            if (sessionId != null) {
                cancelSession(client, sessionId, runId);
            }
            runSessions.remove(runId);
            runClients.remove(runId);
            runInstances.remove(runId);
            runAborted.remove(runId);
            // The run is over: no ask can be delivered to its session anymore, and neither the
            // worker credential nor any one-use write grant may outlive it (C4/R11).
            if (coordinatorBound) {
                permissionCoordinator.unbindRun(runId);
            }
            if (runScopedCredentialService != null) {
                runScopedCredentialService.revoke(runId);
            }
            if (writeGrantService != null) {
                writeGrantService.revoke(runId);
            }
        }
    }

    private TaskResult terminalResult(UUID runId, UUID agentId, String sessionId,
                                      QoderProgressPump.TerminalOutcome outcome) {
        if (outcome.failed()) {
            throw mapRunFailure(runId, outcome);
        }
        // Honest usage (design §4.2, acceptance item 10): only a measured pair may be
        // presented as tokens; unknown counters become 0 placeholders that must never be
        // read as measured (usageReported=false).
        boolean usageReported = outcome.inputTokens() != null && outcome.outputTokens() != null;
        int inputTokens = usageReported ? outcome.inputTokens() : 0;
        int outputTokens = usageReported ? outcome.outputTokens() : 0;
        if (usageReported) {
            log.info("Qoder task {} finished for agent {} ({} input / {} output tokens)",
                    runId, agentId, inputTokens, outputTokens);
        } else {
            log.info("Qoder task {} finished for agent {} — token usage not reported by the provider",
                    runId, agentId);
        }
        return new TaskResult(runId, sessionId, outcome.finalOutput(),
                inputTokens, outputTokens, outcome.aborted(), usageReported);
    }

    @Override
    public void abortTask(UUID runId) {
        QoderBridgeClient client = runClients.get(runId);
        if (client == null) {
            if (!registeredRuns.containsKey(runId)) {
                // Run not even registered yet — nothing to abort.
                log.debug("No in-flight Qoder task found for run {}", runId);
                return;
            }
            // F5: the run is registered for cancellation but its sandbox is still being
            // prepared: record the pending abort that runTask honours right after
            // preparation, before any session is created or prompt is sent.
            runAborted.put(runId, Boolean.TRUE);
            log.info("Qoder abort requested for run {} during sandbox preparation — pending", runId);
            return;
        }
        String sessionId = runSessions.get(runId);
        if (sessionId == null) {
            // Session not created yet (sandbox-prep / session-creation window): record
            // a pending abort that executeTask honors right after createSession.
            runAborted.put(runId, Boolean.TRUE);
            log.info("Qoder abort requested for run {} before session creation — pending", runId);
            return;
        }
        // Mark first: the runner thread must report this run as ABORTED (not as a
        // provider failure) when its event stream ends in reaction to the cancel.
        runAborted.put(runId, Boolean.TRUE);
        stopRun(runId, sessionId, client, runInstances.get(runId));
    }

    /**
     * Cancel a run's bridge session and prove the stop: wait the bounded grace for the
     * event stream to end; only when that cannot be proven is the agent's sandbox killed
     * as the last bounded fallback (design §5.3).
     */
    private void stopRun(UUID runId, String sessionId, QoderBridgeClient client, QoderInstance inst) {
        cancelSession(client, sessionId, runId);
        QoderProgressPump pump = runPumps.get(runId);
        if (pump == null) {
            // The abort landed between session registration and pump start: the prompt is
            // only issued after the pump exists, so the accepted cancel is sufficient —
            // there is no reader to prove stopped, and killing the sandbox is wrong here.
            log.info("Qoder run {} cancelled before its event-stream pump started — cancel is sufficient",
                    runId);
            return;
        }
        boolean stopped = pump.awaitStopped(stopGrace);
        if (stopped) {
            return;
        }
        if (inst != null) {
            log.warn("Qoder run {} did not stop within {}ms of cancel — killing sandbox {} as the last bounded fallback",
                    runId, stopGrace.toMillis(), inst.sandboxId());
            killAndForgetInstance(inst);
        }
    }

    private void cancelSession(QoderBridgeClient client, String sessionId, UUID runId) {
        try {
            client.cancel(sessionId);
            log.info("Cancelled Qoder bridge session {} for run {}", sessionId, runId);
        } catch (Exception e) {
            // Best effort: the pending-abort / deadline path must not mask the original
            // failure with a cancel error; an unproven stop still kills the sandbox.
            log.warn("Failed to cancel Qoder bridge session {} for run {}: {}", sessionId, runId, e.getMessage());
        }
    }

    @Override
    public boolean isHealthy(UUID agentId) {
        QoderInstance inst = instances.get(agentId);
        if (inst == null) {
            return false;
        }
        // Unlike the OpenCode client's boolean probe, the bridge health() throws on
        // failure — a throw here is the unhealthy signal, mapped to false.
        try {
            inst.client().health();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public RuntimeHealth probeRuntimeHealth(UUID agentId) {
        QoderInstance inst = instances.get(agentId);
        if (inst == null) {
            return RuntimeHealth.NOT_STARTED;
        }
        // Deliberately side-effect free: a read-path probe must never tear a sandbox down.
        try {
            inst.client().health();
            return RuntimeHealth.REACHABLE;
        } catch (Exception e) {
            return RuntimeHealth.UNREACHABLE;
        }
    }

    @Override
    public boolean isServiceHealthy() {
        // Service-level probe: the OpenSandbox lifecycle server itself (no agent context).
        return sandboxLifecycle.isServerHealthy();
    }

    @Override
    public void shutdownAgent(UUID agentId) {
        QoderInstance inst = instances.remove(agentId);
        if (inst == null) {
            return;
        }
        // Abort any in-flight runs owned by this agent's instance.
        runInstances.forEach((runId, runInst) -> {
            if (runInst == inst) {
                abortTask(runId);
            }
        });
        destroyInstance(inst.sandboxId());
        closeIfOwned(inst.client());
        log.info("Qoder agent {} shut down (sandbox {})", agentId, inst.sandboxId());
    }

    @PreDestroy
    @Override
    public void shutdownAll() {
        log.info("Shutting down Qoder provider — {} sandboxes to kill", instances.size());
        for (UUID agentId : List.copyOf(instances.keySet())) {
            shutdownAgent(agentId);
        }
        instances.clear();
        preparing.clear();
        prepareExecutor.shutdown();
    }

    // ---- instance lifecycle ----

    private QoderInstance getOrPrepareInstance(UUID agentId, Agent agent) {
        QoderInstance existing = instances.get(agentId);
        if (existing != null) {
            if (isBridgeReachable(existing)) {
                if (mayReuseForCurrentCredential(existing)) {
                    return existing;
                }
            } else {
                log.warn("Existing Qoder instance for agent {} is unhealthy, rebuilding...", agentId);
                killAndForgetInstance(existing);
            }
        }
        // Concurrent runs for the same agent share a single sandbox preparation.
        // Ownership is decided atomically inside `preparing.compute` and the completed
        // future is KEPT in the map (never removed) so a late caller can never observe
        // a null mapping after the owner finished and race to become a second owner
        // (TOCTOU fix — the previous get/putIfAbsent/remove sequence left such a window).
        @SuppressWarnings("unchecked")
        CompletableFuture<QoderInstance>[] ownerHolder = new CompletableFuture[1];
        CompletableFuture<QoderInstance> future = preparing.compute(agentId, (key, previous) -> {
            if (previous != null && !previous.isDone()) {
                return previous; // someone is preparing — join them
            }
            // previous is done (or absent). Reuse only if the instance is still registered
            // and reachable; otherwise become the owner of a fresh preparation.
            QoderInstance inst = instances.get(agentId);
            if (inst != null && isBridgeReachable(inst)) {
                return previous != null ? previous : CompletableFuture.completedFuture(inst);
            }
            CompletableFuture<QoderInstance> fresh = new CompletableFuture<>();
            ownerHolder[0] = fresh;
            return fresh;
        });
        if (ownerHolder[0] != null) {
            // Owner: prepare on the shared executor and complete the future. The completed
            // future stays in `preparing` so late callers reuse it via the compute path
            // above instead of racing to become a second owner.
            try {
                prepareExecutor.execute(() -> {
                    try {
                        ownerHolder[0].complete(prepareInstance(agentId, agent));
                    } catch (Throwable t) {
                        ownerHolder[0].completeExceptionally(t);
                    }
                });
            } catch (RejectedExecutionException e) {
                // Executor shut down (provider teardown) — fail the waiters fast.
                ownerHolder[0].completeExceptionally(e);
            }
        }
        return awaitPreparation(future, agentId);
    }

    private QoderInstance awaitPreparation(CompletableFuture<QoderInstance> future, UUID agentId) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TaskExecutionException(TaskExecutionException.Cause.ABORTED,
                    "Interrupted while preparing the Qoder sandbox for agent " + agentId, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof TaskExecutionException tee) {
                throw tee;
            }
            throw new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                    "Qoder sandbox setup failed for agent " + agentId + ": " + cause.getMessage(), cause);
        }
    }

    private QoderInstance prepareInstance(UUID agentId, Agent agent) {
        // Read the PAT before any sandbox is created: a missing credential is a typed
        // provider error, not a sandbox leak. The instance is stamped with the PAT's
        // one-way hash only, never the PAT itself (F6).
        String pat = readPat();
        String patHash = credentialHash(pat);
        // Per-sandbox random bridge bearer token: the bridge fails closed without one,
        // and the token must never be the PAT (the bridge is reachable from the host).
        String bridgeToken = newBridgeToken();
        Map<String, String> env = Map.of(
                PAT_ENV, pat,
                BRIDGE_TOKEN_ENV, bridgeToken,
                PORT_ENV, String.valueOf(properties.getPort()));
        String sandboxId = sandboxLifecycle.createSandbox(agentId, properties.getImage(), env);
        QoderBridgeClient client = null;
        try {
            String bridgeUrl = sandboxLifecycle.getSandboxUrl(sandboxId, properties.getPort());
            client = clientFactory.apply(bridgeUrl, bridgeToken);
            waitForBridgeReady(client, sandboxId, agentId);
            QoderInstance instance = new QoderInstance(agentId, sandboxId, bridgeUrl, bridgeToken, patHash, client);
            instances.put(agentId, instance);
            log.info("Qoder bridge ready for agent {} (sandbox {}) at {}", agentId, sandboxId, bridgeUrl);
            return instance;
        } catch (TaskExecutionException e) {
            killAndForget(agentId, sandboxId, client);
            throw e;
        } catch (Exception e) {
            killAndForget(agentId, sandboxId, client);
            throw new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                    "Qoder sandbox setup failed for agent " + agentId + ": " + e.getMessage(), e);
        }
    }

    private void killAndForget(UUID agentId, String sandboxId, QoderBridgeClient client) {
        instances.remove(agentId);
        destroyInstance(sandboxId);
        closeIfOwned(client);
    }

    private String readPat() {
        try {
            return credentialService.read(PROVIDER_ID);
        } catch (RuntimeCredentialException e) {
            throw new TaskExecutionException(TaskExecutionException.Cause.PROVIDER_ERROR,
                    "Qoder runtime credential is unavailable: " + e.getMessage(), e);
        }
    }

    /**
     * F6 credential gate for a healthy, registered instance: may it serve the next launch
     * under the currently stored credential?
     *
     * <p>The PAT rides the sandbox container environment, which is fixed at creation time,
     * so a reused sandbox keeps serving the credential of its own era. Re-validated against
     * the one-way credential hash stamped on the instance:
     * <ul>
     *   <li>the current credential is absent — {@link #readPatHash()} fails the launch with
     *       a typed provider error (remove revokes future launches);</li>
     *   <li>the credential is unchanged — the sanctioned reuse path;</li>
     *   <li>the credential changed — the idle sandbox is recreated so the next run's CLI
     *       process inherits the new value;</li>
     *   <li>a session is already running on the instance — it is never disturbed (an
     *       explicit cancel is the only way to end a running session), the rotation applies
     *       to the next run.</li>
     * </ul>
     *
     * @return true when the instance stays as-is (reuse or a run in flight), false when it
     *         was destroyed and a fresh preparation is required
     */
    private boolean mayReuseForCurrentCredential(QoderInstance existing) {
        if (readPatHash().equals(existing.patHash())) {
            return true;
        }
        if (hasInFlightRun(existing)) {
            log.warn("Qoder credential changed for agent {} but sandbox {} has a run in flight"
                            + " — that session keeps its credential; the change applies to the next run",
                    existing.agentId(), existing.sandboxId());
            return true;
        }
        log.info("Qoder credential changed for agent {} — recreating the idle sandbox {} so the"
                + " next run uses the new credential", existing.agentId(), existing.sandboxId());
        killAndForgetInstance(existing);
        return false;
    }

    /** True when a run currently registered to this provider is bound to the instance. */
    private boolean hasInFlightRun(QoderInstance inst) {
        return runInstances.containsValue(inst);
    }

    /** The credential identity of the currently stored PAT; a missing credential fails closed. */
    private String readPatHash() {
        return credentialHash(readPat());
    }

    /**
     * One-way identity of a credential value (SHA-256 hex). Only the hash is ever stamped
     * on an instance or compared — the PAT itself is never stored, logged or echoed.
     */
    private static String credentialHash(String pat) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(pat.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", e);
        }
    }

    private static String newBridgeToken() {
        byte[] bytes = new byte[BRIDGE_TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private boolean isBridgeReachable(QoderInstance inst) {
        try {
            inst.client().health();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Wait until the bridge answers {@code /health} or the ready budget elapses.
     *
     * <p>VERIFY-THEN-START: the image CMD is not relied upon; while bridge readiness is
     * unproven the bridge is started explicitly via
     * {@link SandboxLifecycle#runBackgroundCommand} (the same long-lived-process pattern
     * as the opencode {@code serve} launch). The command inherits the container env, so
     * no secret rides this call. Because that call is fire-and-forget, a command issued
     * before the sandbox exec channel accepts connections is lost silently — hence the
     * {@link #awaitExecdReady} gate first, and a bounded re-issue every
     * {@link #BRIDGE_START_RETRY_INTERVAL} while readiness stays unproven.
     */
    private void waitForBridgeReady(QoderBridgeClient client, String sandboxId, UUID agentId) {
        awaitExecdReady(sandboxId, agentId);
        long deadlineNanos = System.nanoTime() + bridgeReadyTimeout.toNanos();
        long nextStartNanos = System.nanoTime();
        while (true) {
            try {
                client.health();
                return;
            } catch (Exception e) {
                log.debug("Qoder bridge not ready yet for agent {} (sandbox {}): {}", agentId, sandboxId, e.getMessage());
            }
            long now = System.nanoTime();
            if (now >= nextStartNanos) {
                log.info("Qoder bridge not reachable for agent {} (sandbox {}) — starting it: {}",
                        agentId, sandboxId, BRIDGE_START_COMMAND);
                sandboxLifecycle.runBackgroundCommand(sandboxId, BRIDGE_START_COMMAND, Map.of());
                nextStartNanos = now + bridgeStartRetryInterval.toNanos();
            }
            long remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000;
            if (remainingMillis <= 0) {
                break;
            }
            try {
                Thread.sleep(Math.min(bridgeReadyPollInterval.toMillis(), remainingMillis));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                "Qoder bridge did not become ready within " + bridgeReadyTimeout.toSeconds() + "s for agent " + agentId);
    }

    /**
     * Wait until the sandbox exec channel accepts a trivial command.
     *
     * <p>Sandboxes are created with {@code skipHealthCheck=true} (execd lags the sandbox
     * record), and commands issued before execd is up fail at connect time and are lost
     * for the fire-and-forget background launch. Never fatal: if execd stays down the
     * bridge-readiness loop below still produces the typed
     * {@code SANDBOX_UNAVAILABLE} failure within its own budget.
     */
    private void awaitExecdReady(String sandboxId, UUID agentId) {
        long deadlineNanos = System.nanoTime() + EXECD_READY_TIMEOUT.toNanos();
        int attempts = 0;
        while (true) {
            try {
                sandboxLifecycle.runCommand(sandboxId, "true");
                return;
            } catch (Exception e) {
                attempts++;
                log.debug("Sandbox exec channel not ready for agent {} (sandbox {}), attempt {}: {}",
                        agentId, sandboxId, attempts, e.getMessage());
            }
            if (System.nanoTime() >= deadlineNanos) {
                log.warn("Sandbox exec channel still not ready after {}s for agent {} (sandbox {}) —"
                                + " continuing with the bridge readiness probe",
                        EXECD_READY_TIMEOUT.toSeconds(), agentId, sandboxId);
                return;
            }
            try {
                Thread.sleep(EXECD_READY_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void destroyInstance(String sandboxId) {
        try {
            sandboxLifecycle.killSandbox(sandboxId);
        } catch (Exception e) {
            log.warn("Failed to destroy sandbox {}: {}", sandboxId, e.getMessage());
        }
    }

    private void killAndForgetInstance(QoderInstance inst) {
        instances.remove(inst.agentId(), inst);
        destroyInstance(inst.sandboxId());
        closeIfOwned(inst.client());
    }

    /** Close a bridge client this provider owns; caller-injected clients stay open. */
    private void closeIfOwned(QoderBridgeClient client) {
        if (client == null || !ownsClients) {
            return;
        }
        try {
            client.close();
        } catch (Exception e) {
            log.debug("Failed to close Qoder bridge client: {}", e.getMessage());
        }
    }

    /**
     * Start a daemon heartbeat that renews the sandbox TTL while the long-lived run
     * blocks. Renewal failures are non-fatal (logged); the caller must
     * {@code shutdownNow()} the returned executor once the run finishes.
     */
    private ScheduledExecutorService startRenewHeartbeat(QoderInstance inst) {
        Duration interval = properties.getSandboxRenewInterval();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "qoder-sandbox-renew-" + inst.sandboxId());
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(() -> {
            try {
                log.info("Renewing OpenSandbox TTL for sandbox {}", inst.sandboxId());
                sandboxLifecycle.renewSandbox(inst.sandboxId(), RENEW_EXTENSION);
            } catch (Exception e) {
                log.warn("Sandbox TTL renewal failed for {}: {}", inst.sandboxId(), e.getMessage());
            }
        }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        return executor;
    }

    private void publishProgress(RunProgressEvent event) {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(event);
        }
    }

    /**
     * The caller-provided {@link TaskContext#maxDuration()} is the effective deadline
     * (never silently replaced); a {@code null} duration falls back to
     * {@code qoder.max-task-minutes}.
     */
    private Duration resolveMaxDuration(TaskContext context) {
        if (context != null && context.maxDuration() != null) {
            return context.maxDuration();
        }
        return Duration.ofMinutes(properties.getMaxTaskMinutes());
    }

    /** Ceiling to whole seconds: the bridge must never receive a deadline shorter than promised. */
    private static long deadlineSeconds(Duration deadline) {
        return Math.max(1L, (deadline.toMillis() + 999L) / 1000L);
    }

    private TaskExecutionException mapBridgeFailure(UUID runId, QoderBridgeException e) {
        String message = e.getMessage() == null ? e.cause().name() : e.getMessage();
        if (message.contains("UNKNOWN_MODEL")) {
            // The bridge reports the CLI's model rejection as a 400 error string (no
            // first-class code accessor in the frozen client), so match the payload text.
            return new TaskExecutionException(TaskExecutionException.Cause.PROVIDER_ERROR,
                    "Qoder model '" + properties.getModel() + "' was rejected by the CLI (UNKNOWN_MODEL)"
                            + " for run " + runId + ": " + message, e);
        }
        TaskExecutionException.Cause cause = switch (e.cause()) {
            case TIMEOUT -> TaskExecutionException.Cause.TIMEOUT;
            case UNREACHABLE -> TaskExecutionException.Cause.SANDBOX_UNAVAILABLE;
            default -> TaskExecutionException.Cause.PROVIDER_ERROR;
        };
        return new TaskExecutionException(cause, "Qoder bridge call failed for run " + runId + ": " + message, e);
    }

    private TaskExecutionException mapRunFailure(UUID runId, QoderProgressPump.TerminalOutcome outcome) {
        String reason = outcome.failureReason() == null ? "" : outcome.failureReason();
        String code = outcome.failureCode();
        if ("GOVERNANCE_STOP".equals(code) || reason.toLowerCase(Locale.ROOT).contains("governance")) {
            return new TaskExecutionException(TaskExecutionException.Cause.PROVIDER_ERROR,
                    "Qoder governance stop for run " + runId + ": " + reason
                            + (code == null ? "" : " [" + code + "]"));
        }
        StringBuilder message = new StringBuilder("Qoder run ").append(runId).append(" failed");
        if (code != null && !code.isBlank()) {
            message.append(" [").append(code).append(']');
        }
        if (!reason.isBlank()) {
            message.append(": ").append(reason);
        }
        return new TaskExecutionException(TaskExecutionException.Cause.PROVIDER_ERROR, message.toString());
    }

    // ---- worker MCP entry (R11) ----

    /**
     * Design lines 309-311: the unauthenticated operator mode must be unreachable from Qoder
     * sandboxes, otherwise the governed provider refuses to start. The bridge hands the
     * sandbox the {@code /mcp} endpoint, and outside token mode that endpoint admits a
     * header-less caller as the operator identity — the sandbox could bypass worker
     * governance and the one-use write grants entirely. The refusal happens at the run /
     * prepare entry, before any sandbox, CLI process, session or prompt exists. MCP disabled
     * entirely and token mode are both unaffected.
     */
    private void requireGovernedMcpConfiguration() {
        if (mcpProperties == null || !mcpProperties.isEnabled() || mcpProperties.isTokenMode()) {
            return;
        }
        throw new TaskExecutionException(TaskExecutionException.Cause.PROVIDER_ERROR,
                "Qoder refuses to run with MCP enabled in auth mode '" + mcpProperties.getAuthMode()
                        + "': the /mcp endpoint would admit the sandbox as the operator identity."
                        + " Set aria.mcp.auth-mode=token (or disable MCP entirely) for Qoder agents.");
    }

    /**
     * The worker MCP entry needs MCP enabled and a credential source; a manual wiring without
     * the Spring collaborators runs without MCP rather than registering an anonymous entry.
     */
    private boolean workerMcpEnabled() {
        return mcpProperties != null && mcpProperties.isEnabled()
                && runScopedCredentialService != null && mcpProperties.getPort() > 0;
    }

    /**
     * Probe the sandbox-reachable host candidates and build the worker MCP entry for the first
     * one that answers with HTTP 2xx from inside the sandbox (R11). Every candidate is probed
     * with the worker credential exactly as the session will send it, so an accepted candidate
     * has proven both reachability and the credential's acceptance. No candidate accepted →
     * absent entry: a fail-closed run without MCP is preferable to an anonymous entry the
     * sandbox could use to reach the platform MCP surface unauthenticated.
     */
    private List<QoderBridgeClient.McpServer> workerMcpServers(QoderBridgeClient client, UUID runId,
                                                               String workerToken) {
        List<String> candidates =
                hostResolverFactory.apply(mcpProperties.getSandboxHostAddress()).resolveOrdered();
        if (candidates.isEmpty()) {
            log.warn("Qoder run {}: no sandbox-reachable host candidate for the MCP entry —"
                    + " the run proceeds without MCP tools", runId);
            return List.of();
        }
        List<QoderBridgeClient.Header> headers =
                List.of(new QoderBridgeClient.Header("Authorization", "Bearer " + workerToken));
        for (String host : candidates) {
            String url = "http://" + host + ":" + mcpProperties.getPort() + "/mcp";
            QoderBridgeClient.ProbeResult probe;
            try {
                probe = client.probe(url, headers);
            } catch (QoderBridgeException e) {
                // A probe that never reached the bridge says nothing about the candidate; the
                // bridge being down will fail createSession anyway, so keep iterating.
                log.warn("Qoder run {}: MCP probe of {} answered with {}: {}",
                        runId, url, e.cause(), e.getMessage());
                continue;
            }
            if (probe.reachable() && probe.status() != null && probe.status() / 100 == 2) {
                log.info("Qoder run {}: sandbox reaches the MCP endpoint at {}", runId, url);
                return List.of(new QoderBridgeClient.McpServer(QoderBridgeClient.PLATFORM_SERVER_NAME,
                        url, headers));
            }
            log.warn("Qoder run {}: MCP candidate {} unusable (reachable={}, status={}, detail={})",
                    runId, url, probe.reachable(), probe.status(), probe.detail());
        }
        log.warn("Qoder run {}: no MCP candidate answered 2xx from inside the sandbox —"
                + " the run proceeds without MCP tools", runId);
        return List.of();
    }

    // ---- test-only accessors ----

    /** Test-only: expose the live per-agent instance registry. */
    Map<UUID, QoderInstance> instancesForTest() {
        return instances;
    }

    /** Test-only: expose the live runId → bridge session registry. */
    Map<UUID, String> runSessionsForTest() {
        return runSessions;
    }

    /** Test-only: expose the live runId → bridge client registry. */
    Map<UUID, QoderBridgeClient> runClientsForTest() {
        return runClients;
    }

    /** Test-only: expose the live runId → sandbox instance registry. */
    Map<UUID, QoderInstance> runInstancesForTest() {
        return runInstances;
    }

    /** Test-only: expose the live runId → progress pump registry. */
    Map<UUID, QoderProgressPump> runPumpsForTest() {
        return runPumps;
    }

    /** Test-only: expose the in-flight/shared sandbox preparations, keyed by agentId. */
    Map<UUID, CompletableFuture<QoderInstance>> preparingForTest() {
        return preparing;
    }

    /** Test-only: expose the runs registered for cancellation while their sandbox is prepared. */
    Map<UUID, Boolean> runRegistrationsForTest() {
        return registeredRuns;
    }

    /** Test-only: shrink the bridge ready-wait budget. */
    void setBridgeReadyTimeoutForTest(Duration timeout) {
        this.bridgeReadyTimeout = timeout;
    }

    /** Test-only: shrink the bridge ready poll interval. */
    void setBridgeReadyPollIntervalForTest(Duration interval) {
        this.bridgeReadyPollInterval = interval;
    }

    /** Test-only: shrink the bridge-start re-issue interval. */
    void setBridgeStartRetryIntervalForTest(Duration interval) {
        this.bridgeStartRetryInterval = interval;
    }

    /** Test-only: shrink the cancel-to-kill grace. */
    void setStopGraceForTest(Duration grace) {
        this.stopGrace = grace;
    }

    /** Test-only: pin the host-candidate source so the MCP probe order is deterministic. */
    void setHostResolverFactoryForTest(Function<String, SandboxHostResolver> factory) {
        this.hostResolverFactory = factory;
    }

    /**
     * Lifecycle state of an agent's Qoder sandbox + bridge instance.
     *
     * @param agentId     the agent owning the sandbox
     * @param sandboxId   OpenSandbox sandbox id
     * @param bridgeUrl   host-reachable base URL of the in-sandbox bridge
     * @param bridgeToken per-sandbox bearer token (never logged)
     * @param patHash     one-way hash of the credential the sandbox was built with (F6)
     * @param client      bridge client targeting this instance
     */
    record QoderInstance(UUID agentId, String sandboxId, String bridgeUrl, String bridgeToken,
                         String patHash, QoderBridgeClient client) {

        /** Redacting toString: the generated one would print the bridge bearer token. */
        @Override
        public String toString() {
            return "QoderInstance[agentId=" + agentId
                    + ", sandboxId=" + sandboxId
                    + ", bridgeUrl=" + bridgeUrl
                    + ", bridgeToken=<redacted>"
                    + ", patHash=<redacted>"
                    + ", client=" + client + "]";
        }
    }
}
