package io.aria.conductor.execution.engine;

import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentSession;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Context object carried through the agent loop iterations.
 */
public class RunContext {

    private final UUID runId;
    private final UUID agentId;
    private final Agent agent;
    private AgentSession session;
    private String conversationId;
    private String intent;
    private int iterationCount;
    private int maxIterations;
    private long totalTokensUsed;
    private final List<String> errors;
    private final Instant startTime;
    private volatile boolean paused;
    private volatile boolean cancelled;
    private CompletableFuture<Void> pauseBlocker;
    private String lastAssistantResponse;
    private UUID currentToolCallId;
    private List<UUID> currentTurnToolCallIds;
    private List<String> cachedSkillNames;
    private String workspaceDir;
    private io.aria.conductor.common.model.HarnessProfile harnessProfile;

    // Consecutive same-error tracking (prevents infinite retry loops)
    private String lastToolError;
    private int consecutiveSameErrorCount;

    public RunContext(UUID runId, UUID agentId, Agent agent, AgentSession session) {
        this(runId, agentId, agent, session, 50);
    }

    public RunContext(UUID runId, UUID agentId, Agent agent, AgentSession session, int maxIterations) {
        this(runId, agentId, agent, session, maxIterations, null);
    }

    public RunContext(UUID runId, UUID agentId, Agent agent, AgentSession session, int maxIterations, String conversationId) {
        this.runId = runId;
        this.agentId = agentId;
        this.agent = agent;
        this.session = session;
        this.conversationId = conversationId;
        this.iterationCount = 0;
        this.maxIterations = maxIterations;
        this.totalTokensUsed = 0;
        this.errors = new ArrayList<>();
        this.startTime = Instant.now();
        this.paused = false;
        this.cancelled = false;
        this.pauseBlocker = null;
    }

    public UUID getRunId() { return runId; }
    public UUID getAgentId() { return agentId; }
    public Agent getAgent() { return agent; }
    public AgentSession getSession() { return session; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
    public int getIterationCount() { return iterationCount; }
    public int getMaxIterations() { return maxIterations; }
    public long getTotalTokensUsed() { return totalTokensUsed; }
    public List<String> getErrors() { return errors; }
    public Instant getStartTime() { return startTime; }
    public boolean isPaused() { return paused; }
    public boolean isCancelled() { return cancelled; }

    public void setSession(AgentSession session) { this.session = session; }

    public void incrementIteration() { this.iterationCount++; }

    public void addTokensUsed(int inputTokens, int outputTokens) {
        this.totalTokensUsed += inputTokens + outputTokens;
    }

    public void addError(String error) { this.errors.add(error); }

    public String getLastAssistantResponse() { return lastAssistantResponse; }
    public void setLastAssistantResponse(String response) { this.lastAssistantResponse = response; }

    public UUID getCurrentToolCallId() { return currentToolCallId; }
    public void setCurrentToolCallId(UUID toolCallId) { this.currentToolCallId = toolCallId; }

    public List<UUID> getCurrentTurnToolCallIds() { return currentTurnToolCallIds; }
    public void setCurrentTurnToolCallIds(List<UUID> ids) { this.currentTurnToolCallIds = ids; }

    public List<String> getCachedSkillNames() { return cachedSkillNames; }
    public void setCachedSkillNames(List<String> names) { this.cachedSkillNames = names; }

    public String getLastToolError() { return lastToolError; }
    public void setLastToolError(String e) { this.lastToolError = e; }
    public int getConsecutiveSameErrorCount() { return consecutiveSameErrorCount; }
    public void setConsecutiveSameErrorCount(int c) { this.consecutiveSameErrorCount = c; }

    public String getWorkspaceDir() { return workspaceDir; }
    public void setWorkspaceDir(String workspaceDir) { this.workspaceDir = workspaceDir; }

    /**
     * The resolved harness profile for this run (tool steering, self-verify escalation, budgets).
     * May be null for runs created before profile resolution; callers should fall back to
     * {@link io.aria.conductor.common.model.HarnessProfile#defaults()}.
     */
    public io.aria.conductor.common.model.HarnessProfile getHarnessProfile() { return harnessProfile; }
    public void setHarnessProfile(io.aria.conductor.common.model.HarnessProfile harnessProfile) { this.harnessProfile = harnessProfile; }

    /**
     * Pause the run — creates a new CompletableFuture that blocks the loop.
     */
    public synchronized void pause() {
        this.paused = true;
        if (this.pauseBlocker == null || this.pauseBlocker.isDone()) {
            this.pauseBlocker = new CompletableFuture<>();
        }
    }

    /**
     * Resume the run — completes the pause blocker to unblock the loop.
     */
    public synchronized void resume() {
        this.paused = false;
        if (this.pauseBlocker != null && !this.pauseBlocker.isDone()) {
            this.pauseBlocker.complete(null);
        }
    }

    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }

    /**
     * Blocks the virtual thread while the run is paused.
     * Returns normally when resumed.
     * Throws if the future is cancelled (run cancelled while paused).
     */
    public void awaitResume() throws InterruptedException {
        CompletableFuture<Void> blocker;
        synchronized (this) {
            if (!paused) return;
            blocker = this.pauseBlocker;
        }
        try {
            blocker.get(); // blocks virtual thread, not OS thread
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            throw new InterruptedException("Run interrupted while paused");
        }
    }
}