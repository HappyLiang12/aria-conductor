package io.aria.conductor.execution.adk.opencode;

import io.aria.conductor.common.model.Agent;
import io.aria.conductor.execution.adk.AbstractAdkProvider;
import io.aria.conductor.execution.adk.TaskContext;
import io.aria.conductor.execution.adk.TaskExecutionException;
import io.aria.conductor.execution.adk.TaskResult;
import io.aria.conductor.execution.llm.LlmMessage;
import io.aria.conductor.execution.llm.LlmResponse;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * OpenCode agent provider — one {@code opencode serve} instance per agent,
 * running inside an OpenSandbox sandbox, communicating over HTTP.
 *
 * <p>OpenCode is an end-to-end agent (task-level semantics), so this provider
 * implements {@link #supportsTaskExecution()} = {@code true} and drives runs
 * through {@link #executeTask}. The turn-level {@link #call} is unsupported
 * and throws {@link UnsupportedOperationException}.
 *
 * <p>Lifecycle (mirrors the LangChain pattern): each agent gets a sandbox with
 * {@code opencode serve} bound to {@code OpenCodeProperties#port}; health is
 * probed via {@code GET /global/health}; after
 * {@value #RESTART_AFTER_FAILURES} consecutive failures the sandbox is
 * destroyed and rebuilt on the next {@link #prepareAgent}.
 */
@Slf4j
@Component
public class OpenCodeAdkProvider extends AbstractAdkProvider {

    /** Threshold of consecutive failed health probes before triggering a sandbox rebuild. */
    private static final int RESTART_AFTER_FAILURES = 3;
    /** Max time to wait for the serve process to become ready. */
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(60);
    /** Health poll interval while waiting for readiness. */
    private static final Duration READY_POLL_INTERVAL = Duration.ofMillis(500);
    /** Default per-request timeout for the serve HTTP client. */
    private static final Duration HTTP_TIMEOUT = Duration.ofMinutes(5);
    /** TTL extension requested on each renewal during a long task (matches the sandbox TTL). */
    private static final Duration RENEW_EXTENSION = Duration.ofMinutes(30);
    /** Relative workspace base dir (resolved against the agent-control-tower working dir). */
    private static final String WORKSPACE_BASE = "act-app/data/workspaces";

    private final OpenCodeProperties properties;
    private final OpenCodeSandboxManager sandboxManager;
    /** Injected in tests; {@code null} in production (per-instance clients are created). */
    private final OpenCodeHttpClient fixedHttpClient;

    private final Map<UUID, OpenCodeInstance> instances = new ConcurrentHashMap<>();
    private final Map<UUID, String> runSessions = new ConcurrentHashMap<>();
    private final Map<UUID, OpenCodeHttpClient> runClients = new ConcurrentHashMap<>();
    /** Pending aborts recorded while a run's session was not yet created (see {@link #abortTask}). */
    private final Map<UUID, Boolean> runAborted = new ConcurrentHashMap<>();
    /** In-flight sandbox preparations, keyed by agentId — concurrent runs share one prepare. */
    private final Map<UUID, CompletableFuture<OpenCodeInstance>> preparing = new ConcurrentHashMap<>();
    /** Executor for sandbox preparation (sleep-based health polling must not block common pool). */
    private final ExecutorService prepareExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private Path workspaceBase = Paths.get(WORKSPACE_BASE);
    /** Ready-wait budget (overridable in tests). */
    private Duration readyTimeout = READY_TIMEOUT;
    /** Health poll interval while waiting for readiness (overridable in tests). */
    private Duration readyPollInterval = READY_POLL_INTERVAL;

    /** Spring constructor. */
    @Autowired
    public OpenCodeAdkProvider(OpenCodeProperties properties) {
        this(properties, new OpenCodeSandboxManager(
                properties.getSandboxServerUrl(), properties.getSandboxApiKey()), null);
    }

    /**
     * Test constructor — allows injecting a mocked {@link OpenCodeSandboxManager}
     * and a fixed {@link OpenCodeHttpClient}.
     */
    OpenCodeAdkProvider(OpenCodeProperties properties,
                        OpenCodeSandboxManager sandboxManager,
                        OpenCodeHttpClient httpClient) {
        this.properties = properties;
        this.sandboxManager = sandboxManager;
        this.fixedHttpClient = httpClient;
    }

    @Override
    public String providerId() {
        return "opencode";
    }

    @Override
    public boolean supportsTaskExecution() {
        return true;
    }

    @Override
    public void prepareAgent(UUID agentId, Agent agent) {
        getOrPrepareInstance(agentId, agent);
    }

    @Override
    public LlmResponse call(UUID agentId, List<LlmMessage> messages, List<Map<String, Object>> tools) {
        throw new UnsupportedOperationException(
                "OpenCode provider does not support turn-level call semantics; use executeTask(...)");
    }

    @Override
    public TaskResult executeTask(Agent agent, UUID runId, String taskPrompt, TaskContext context) {
        if (agent == null || runId == null) {
            throw new TaskExecutionException(TaskExecutionException.Cause.PROVIDER_ERROR,
                    "agent and runId must not be null");
        }
        OpenCodeInstance inst = getOrPrepareInstance(agent.getId(), agent);
        OpenCodeHttpClient client = clientFor(inst);
        // Register the client before createSession so a cancel landing in the
        // sandbox-prep / session-creation window is recorded (pending abort) and
        // honored immediately after the session exists — instead of being lost
        // because runSessions was not yet populated.
        runClients.put(runId, client);

        String sessionId = client.createSession("run-" + runId);
        runSessions.put(runId, sessionId);
        // A cancel that arrived before the session existed must terminate the run
        // right away rather than letting it execute in the sandbox.
        if (runAborted.remove(runId) != null) {
            abortSession(client, sessionId, runId);
            throw new TaskExecutionException(TaskExecutionException.Cause.ABORTED,
                    "Run " + runId + " cancelled before execution started");
        }
        log.info("OpenCode task {} started for agent {} (session {})", runId, agent.getId(), sessionId);
        try {
            String systemPrompt = buildSystemPrompt(agent, context);
            Duration deadline = resolveMaxDuration(context);
            // Renew the sandbox TTL while the long-lived synchronous sendMessage
            // blocks — the SDK's own heartbeat fires ~9s too late at the 30-minute
            // TTL boundary (R3-F2).
            ScheduledExecutorService renewExecutor = startRenewHeartbeat(inst);
            try {
                OpenCodeHttpClient.MessageResponse resp =
                        client.sendMessage(sessionId, systemPrompt, taskPrompt, deadline);
                log.info("OpenCode task {} finished for agent {} ({} input / {} output tokens)",
                        runId, agent.getId(), resp.inputTokens(), resp.outputTokens());
                return new TaskResult(runId, sessionId, resp.finalOutput(),
                        resp.inputTokens(), resp.outputTokens(), false);
            } finally {
                renewExecutor.shutdownNow();
            }
        } catch (TaskExecutionException e) {
            if (e.cause() == TaskExecutionException.Cause.TIMEOUT) {
                log.warn("OpenCode task {} timed out for agent {} — aborting", runId, agent.getId());
                abortTask(runId);
            }
            throw e;
        } finally {
            runSessions.remove(runId);
            runClients.remove(runId);
            runAborted.remove(runId);
        }
    }

    @Override
    public void abortTask(UUID runId) {
        OpenCodeHttpClient client = runClients.get(runId);
        if (client == null) {
            // Run not even registered yet — nothing to abort (still in sandbox prep).
            log.debug("No in-flight OpenCode task found for run {}", runId);
            return;
        }
        String sessionId = runSessions.get(runId);
        if (sessionId == null) {
            // Session not created yet (sandbox-prep / session-creation window): record
            // a pending abort that executeTask honors right after createSession.
            runAborted.put(runId, Boolean.TRUE);
            log.info("OpenCode abort requested for run {} before session creation — pending", runId);
            return;
        }
        abortSession(client, sessionId, runId);
    }

    private void abortSession(OpenCodeHttpClient client, String sessionId, UUID runId) {
        try {
            client.abortSession(sessionId);
            log.info("Aborted OpenCode session {} for run {}", sessionId, runId);
        } catch (Exception e) {
            log.warn("Failed to abort OpenCode session {} for run {}: {}", sessionId, runId, e.getMessage());
        }
    }

    @Override
    public boolean isHealthy(UUID agentId) {
        OpenCodeInstance inst = instances.get(agentId);
        if (inst == null) {
            return false;
        }
        boolean healthy;
        try {
            healthy = inst.client().isHealthy();
        } catch (Exception e) {
            healthy = false;
        }
        int failures = healthy ? 0 : inst.failureCount() + 1;
        instances.put(agentId, new OpenCodeInstance(
                inst.sandboxId(), healthy, Instant.now(), failures, inst.client()));
        if (!healthy && failures >= RESTART_AFTER_FAILURES) {
            log.warn("OpenCode sandbox for agent {} unhealthy after {} consecutive failures — destroying for rebuild",
                    agentId, failures);
            destroyInstance(inst.sandboxId());
            closeIfOwned(inst.client());
            instances.remove(agentId);
        }
        return healthy;
    }

    @Override
    public boolean isServiceHealthy() {
        // Service-level probe: the OpenSandbox lifecycle server itself (no agent /
        // sandbox context). Never throws — an unreachable server reports false.
        return sandboxManager.isServerHealthy();
    }

    @Override
    public void shutdownAgent(UUID agentId) {
        OpenCodeInstance inst = instances.remove(agentId);
        if (inst == null) {
            return;
        }
        // Abort any in-flight tasks owned by this agent's client
        runClients.forEach((runId, client) -> {
            if (client == inst.client()) {
                abortTask(runId);
            }
        });
        sandboxManager.killSandbox(inst.sandboxId());
        // Close the per-instance HTTP client (owned by this provider). The fixed
        // test client is injected and must stay open.
        closeIfOwned(inst.client());
        log.info("OpenCode agent {} shut down (sandbox {})", agentId, inst.sandboxId());
    }

    @PreDestroy
    @Override
    public void shutdownAll() {
        log.info("Shutting down OpenCode provider — {} sandboxes to kill", instances.size());
        for (UUID agentId : List.copyOf(instances.keySet())) {
            shutdownAgent(agentId);
        }
        instances.clear();
        preparing.clear();
        prepareExecutor.shutdown();
    }

    // ---- instance lifecycle ----

    private OpenCodeInstance getOrPrepareInstance(UUID agentId, Agent agent) {
        OpenCodeInstance existing = instances.get(agentId);
        // Reuse a cached instance only when it both reported healthy on the last
        // probe AND a fresh probe succeeds right now — a dead sandbox must fall
        // through to the destroy-and-rebuild path below instead of being reused.
        if (existing != null && existing.healthy() && existing.client().isHealthy()) {
            return existing;
        }
        if (existing != null) {
            log.warn("Existing OpenCode instance for agent {} is unhealthy, rebuilding...", agentId);
            destroyInstance(existing.sandboxId());
            instances.remove(agentId);
        }
        // Concurrent runs for the same agent share a single sandbox preparation.
        // Ownership is decided atomically inside `preparing.compute` and the completed
        // future is KEPT in the map (never removed) so a late caller can never observe
        // a null mapping after the owner finished and race to become a second owner
        // (TOCTOU fix — the previous get/putIfAbsent/remove sequence left such a window).
        CompletableFuture<OpenCodeInstance>[] ownerHolder = new CompletableFuture[1];
        CompletableFuture<OpenCodeInstance> future = preparing.compute(agentId, (k, prev) -> {
            if (prev != null && !prev.isDone()) {
                return prev; // someone is preparing — join them
            }
            // prev is done (or absent). Reuse only if the instance is still registered.
            OpenCodeInstance inst = instances.get(agentId);
            if (inst != null && inst.healthy()) {
                return prev != null ? prev : CompletableFuture.completedFuture(inst);
            }
            // prev done but instance gone (destroyed/rebuild), or nothing yet — become owner.
            CompletableFuture<OpenCodeInstance> fresh = new CompletableFuture<>();
            ownerHolder[0] = fresh;
            return fresh;
        });
        if (ownerHolder[0] != null) {
            // Owner: prepare on the shared executor and complete the future. The
            // completed future stays in `preparing` so late callers reuse it via the
            // compute path above instead of racing to become a second owner.
            try {
                prepareExecutor.execute(() -> {
                    try {
                        ownerHolder[0].complete(prepareInstance(agentId, agent));
                    } catch (Throwable t) {
                        ownerHolder[0].completeExceptionally(t);
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // Executor shut down (provider teardown) — fail the waiters fast.
                ownerHolder[0].completeExceptionally(e);
            }
        }
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TaskExecutionException(TaskExecutionException.Cause.ABORTED,
                    "Interrupted while preparing OpenCode sandbox for agent " + agentId, e);
        } catch (ExecutionException e) {
            // prepareInstance throws TaskExecutionException — unwrap and rethrow as-is
            // to preserve the caller-visible cause semantics.
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof TaskExecutionException tee) {
                throw tee;
            }
            throw new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                    "OpenCode sandbox setup failed for agent " + agentId + ": " + cause.getMessage(), cause);
        }
    }

    private OpenCodeInstance prepareInstance(UUID agentId, Agent agent) {
        Path workspace = workspaceBase.resolve(agentId.toString());
        try {
            Files.createDirectories(workspace);
        } catch (IOException e) {
            throw new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                    "Cannot create workspace dir " + workspace + ": " + e.getMessage(), e);
        }

        String sandboxId = sandboxManager.createSandbox(agentId, properties.getImage(), properties.getSandboxEnv());
        OpenCodeHttpClient client = null;
        try {
            sandboxManager.uploadWorkspace(agentId, workspace);
            sandboxManager.runServeCommand(sandboxId, properties.getPort());
            String serveUrl = sandboxManager.getSandboxUrl(sandboxId, properties.getPort());
            client = clientForUrl(serveUrl);
            waitForHealth(client, agentId);
            OpenCodeInstance instance = new OpenCodeInstance(sandboxId, true, Instant.now(), 0, client);
            instances.put(agentId, instance);
            log.info("OpenCode sandbox {} ready for agent {} at {}", sandboxId, agentId, serveUrl);
            return instance;
        } catch (TaskExecutionException e) {
            destroyInstance(sandboxId);
            closeIfOwned(client);
            instances.remove(agentId);
            throw e;
        } catch (Exception e) {
            destroyInstance(sandboxId);
            closeIfOwned(client);
            instances.remove(agentId);
            throw new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                    "OpenCode sandbox setup failed for agent " + agentId + ": " + e.getMessage(), e);
        }
    }

    /**
     * Poll the serve health endpoint until ready or {@link #readyTimeout} elapses.
     *
     * <p>Budget is enforced on the wall clock: each {@code isHealthy()} probe may
     * itself block for up to the client's HTTP timeout (3s), so an attempt-count
     * budget ({@code readyTimeout / pollInterval}) would stretch the real wait far
     * beyond the declared {@code readyTimeout} (observed ~7 min for a 60s budget).
     */
    private void waitForHealth(OpenCodeHttpClient client, UUID agentId) {
        long deadlineNanos = System.nanoTime() + readyTimeout.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            if (client.isHealthy()) {
                return;
            }
            long remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000;
            if (remainingMillis <= 0) {
                break;
            }
            try {
                Thread.sleep(Math.min(readyPollInterval.toMillis(), remainingMillis));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                "opencode serve did not become ready within " + readyTimeout.toSeconds() + "s for agent " + agentId);
    }

    private void destroyInstance(String sandboxId) {
        try {
            sandboxManager.killSandbox(sandboxId);
        } catch (Exception e) {
            log.warn("Failed to destroy sandbox {}: {}", sandboxId, e.getMessage());
        }
    }

    /**
     * Close a per-instance {@link OpenCodeHttpClient} unless it is the injected
     * fixed test client (owned by the test, must stay open). Never throws.
     */
    private void closeIfOwned(OpenCodeHttpClient client) {
        if (client == null || client == fixedHttpClient) {
            return;
        }
        try {
            client.close();
        } catch (Exception e) {
            log.debug("Failed to close OpenCode HTTP client: {}", e.getMessage());
        }
    }

    private OpenCodeHttpClient clientFor(OpenCodeInstance inst) {
        return fixedHttpClient != null ? fixedHttpClient : inst.client();
    }

    /**
     * Start a daemon heartbeat that renews the sandbox TTL while {@code sendMessage}
     * blocks on the long-lived synchronous task (R3-F2).
     *
     * <p>Renewal failures are non-fatal (logged) — the hard failure surfaces through
     * {@code sendMessage}. The returned executor must be {@code shutdownNow()}ed by
     * the caller once the task finishes.
     */
    private ScheduledExecutorService startRenewHeartbeat(OpenCodeInstance inst) {
        Duration interval = properties.getSandboxRenewInterval();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "opencode-sandbox-renew-" + inst.sandboxId());
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(() -> {
            try {
                log.info("Renewing OpenSandbox TTL for sandbox {}", inst.sandboxId());
                sandboxManager.renewSandbox(inst.sandboxId(), RENEW_EXTENSION);
            } catch (Exception e) {
                log.warn("Sandbox TTL renewal failed for {}: {}", inst.sandboxId(), e.getMessage());
            }
        }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        return executor;
    }

    private OpenCodeHttpClient clientForUrl(String serveUrl) {
        return fixedHttpClient != null ? fixedHttpClient : new OpenCodeHttpClient(serveUrl, HTTP_TIMEOUT);
    }

    private Duration resolveMaxDuration(TaskContext context) {
        // The round budget is a prompt-level constraint only (see buildSystemPrompt);
        // it must NOT be translated into a wall-clock deadline, which would silently
        // cap the run below the configured max duration / max-task-minutes.
        if (context != null && context.maxDuration() != null) {
            return context.maxDuration();
        }
        return Duration.ofMinutes(properties.getMaxTaskMinutes());
    }

    private String buildSystemPrompt(Agent agent, TaskContext context) {
        StringBuilder sb = new StringBuilder(
                "You are an autonomous coding agent working on behalf of Aria Conductor.");
        if (agent != null) {
            if (agent.getName() != null && !agent.getName().isBlank()) {
                sb.append("\nAgent name: ").append(agent.getName());
            }
            if (agent.getRole() != null && !agent.getRole().isBlank()) {
                sb.append("\nRole: ").append(agent.getRole());
            }
            if (agent.getDescription() != null && !agent.getDescription().isBlank()) {
                sb.append("\nDescription: ").append(agent.getDescription());
            }
        }
        if (context != null && context.maxRounds() > 0) {
            sb.append("\nYou have at most ").append(context.maxRounds())
                    .append(" assistant turns to complete the task. Stop when the limit")
                    .append(" is reached even if the task appears incomplete.");
        }
        sb.append("\nYou operate inside an isolated sandbox workspace (/workspace)."
                + " Permission requests default to denied.");
        return sb.toString();
    }

    // ---- test-only accessors ----

    /** Test-only: expose the live instance registry. */
    Map<UUID, OpenCodeInstance> instancesForTest() {
        return instances;
    }

    /** Test-only: directly insert an instance. */
    void putInstanceForTest(UUID agentId, OpenCodeInstance inst) {
        instances.put(agentId, inst);
    }

    /** Test-only: override the workspace base directory (defaults to act-app/data/workspaces). */
    void setWorkspaceBaseForTest(Path base) {
        this.workspaceBase = base;
    }

    /** Test-only: shrink the ready-wait budget. */
    void setReadyTimeoutForTest(Duration timeout) {
        this.readyTimeout = timeout;
    }

    /** Test-only: shrink the ready poll interval. */
    void setReadyPollIntervalForTest(Duration interval) {
        this.readyPollInterval = interval;
    }

    /**
     * Lifecycle state of an agent's OpenCode sandbox instance.
     *
     * @param sandboxId         OpenSandbox sandbox id
     * @param healthy           most recent health probe outcome
     * @param lastHealthCheck   timestamp of the most recent health probe
     * @param failureCount      consecutive failed health probes
     * @param client            HTTP client targeting this instance's serve endpoint
     */
    record OpenCodeInstance(String sandboxId, boolean healthy, Instant lastHealthCheck,
                            int failureCount, OpenCodeHttpClient client) {
    }
}
