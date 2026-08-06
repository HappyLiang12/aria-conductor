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
import java.util.concurrent.ConcurrentHashMap;

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
    /** Relative workspace base dir (resolved against the agent-control-tower working dir). */
    private static final String WORKSPACE_BASE = "act-app/data/workspaces";

    private final OpenCodeProperties properties;
    private final OpenCodeSandboxManager sandboxManager;
    /** Injected in tests; {@code null} in production (per-instance clients are created). */
    private final OpenCodeHttpClient fixedHttpClient;

    private final Map<UUID, OpenCodeInstance> instances = new ConcurrentHashMap<>();
    private final Map<UUID, String> runSessions = new ConcurrentHashMap<>();
    private final Map<UUID, OpenCodeHttpClient> runClients = new ConcurrentHashMap<>();

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

        String sessionId = client.createSession("run-" + runId);
        runSessions.put(runId, sessionId);
        runClients.put(runId, client);
        log.info("OpenCode task {} started for agent {} (session {})", runId, agent.getId(), sessionId);
        try {
            String systemPrompt = buildSystemPrompt(agent);
            Duration deadline = resolveMaxDuration(context);
            OpenCodeHttpClient.MessageResponse resp =
                    client.sendMessage(sessionId, systemPrompt, taskPrompt, deadline);
            log.info("OpenCode task {} finished for agent {} ({} input / {} output tokens)",
                    runId, agent.getId(), resp.inputTokens(), resp.outputTokens());
            return new TaskResult(runId, sessionId, resp.finalOutput(),
                    resp.inputTokens(), resp.outputTokens(), false);
        } catch (TaskExecutionException e) {
            if (e.cause() == TaskExecutionException.Cause.TIMEOUT) {
                log.warn("OpenCode task {} timed out for agent {} — aborting", runId, agent.getId());
                abortTask(runId);
            }
            throw e;
        } finally {
            runSessions.remove(runId);
            runClients.remove(runId);
        }
    }

    @Override
    public void abortTask(UUID runId) {
        String sessionId = runSessions.get(runId);
        OpenCodeHttpClient client = runClients.get(runId);
        if (sessionId == null || client == null) {
            log.debug("No in-flight OpenCode task found for run {}", runId);
            return;
        }
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
            instances.remove(agentId);
        }
        return healthy;
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
    }

    // ---- instance lifecycle ----

    private OpenCodeInstance getOrPrepareInstance(UUID agentId, Agent agent) {
        OpenCodeInstance existing = instances.get(agentId);
        if (existing != null && existing.healthy()) {
            return existing;
        }
        if (existing != null) {
            log.warn("Existing OpenCode instance for agent {} is unhealthy, rebuilding...", agentId);
            destroyInstance(existing.sandboxId());
            instances.remove(agentId);
        }
        return prepareInstance(agentId, agent);
    }

    private OpenCodeInstance prepareInstance(UUID agentId, Agent agent) {
        Path workspace = workspaceBase.resolve(agentId.toString());
        try {
            Files.createDirectories(workspace);
        } catch (IOException e) {
            throw new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                    "Cannot create workspace dir " + workspace + ": " + e.getMessage(), e);
        }

        String sandboxId = sandboxManager.createSandbox(agentId, properties.getImage());
        try {
            sandboxManager.uploadWorkspace(agentId, workspace);
            sandboxManager.runServeCommand(sandboxId, properties.getPort());
            String serveUrl = sandboxManager.getSandboxUrl(sandboxId, properties.getPort());
            OpenCodeHttpClient client = clientForUrl(serveUrl);
            waitForHealth(client, agentId);
            OpenCodeInstance instance = new OpenCodeInstance(sandboxId, true, Instant.now(), 0, client);
            instances.put(agentId, instance);
            log.info("OpenCode sandbox {} ready for agent {} at {}", sandboxId, agentId, serveUrl);
            return instance;
        } catch (TaskExecutionException e) {
            destroyInstance(sandboxId);
            instances.remove(agentId);
            throw e;
        } catch (Exception e) {
            destroyInstance(sandboxId);
            instances.remove(agentId);
            throw new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                    "OpenCode sandbox setup failed for agent " + agentId + ": " + e.getMessage(), e);
        }
    }

    /** Poll the serve health endpoint until ready or {@link #readyTimeout} elapses. */
    private void waitForHealth(OpenCodeHttpClient client, UUID agentId) {
        long maxAttempts = Math.max(1, readyTimeout.toMillis() / readyPollInterval.toMillis());
        for (int i = 0; i < maxAttempts; i++) {
            if (client.isHealthy()) {
                return;
            }
            try {
                Thread.sleep(readyPollInterval.toMillis());
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

    private OpenCodeHttpClient clientFor(OpenCodeInstance inst) {
        return fixedHttpClient != null ? fixedHttpClient : inst.client();
    }

    private OpenCodeHttpClient clientForUrl(String serveUrl) {
        return fixedHttpClient != null ? fixedHttpClient : new OpenCodeHttpClient(serveUrl, HTTP_TIMEOUT);
    }

    private Duration resolveMaxDuration(TaskContext context) {
        if (context != null && context.maxDuration() != null) {
            return context.maxDuration();
        }
        return Duration.ofMinutes(properties.getMaxTaskMinutes());
    }

    private String buildSystemPrompt(Agent agent) {
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
