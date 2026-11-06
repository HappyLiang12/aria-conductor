package io.aria.conductor.execution.engine;

import io.aria.conductor.common.exception.ActionableFailure;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.execution.llm.LlmMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Per-run state kept by the engine for an active coordinated run: identity,
 * intent, token and iteration accounting, and the conversation material used
 * for message assembly.
 *
 * <p>Runtime control (pause / resume / cancel) belongs to the run coordinator
 * and is verified through {@code RunRuntimeControlPort}, so it is not mirrored
 * here.
 */
public class RunContext {

    private final UUID runId;
    private final UUID agentId;
    private final Agent agent;
    private final String conversationId;
    private String intent;
    private int iterationCount;
    private long totalTokensUsed;
    private final List<String> errors = new ArrayList<>();
    private String lastAssistantResponse;
    /**
     * Stable identity of the actionable failure recorded by
     * {@link #addError(Throwable)}.
     */
    private String errorCode;
    private String errorSurface;
    /**
     * The conversation turns that precede this run, supplied by the caller for
     * message assembly only. They are never persisted as this run's trajectory
     * rows: the run's own rows are its request and its assistant turns, so the
     * conversation timeline (aggregated across runs) stays free of duplicates.
     */
    private List<LlmMessage> priorContext = List.of();

    public RunContext(UUID runId, UUID agentId, Agent agent, String conversationId) {
        this.runId = runId;
        this.agentId = agentId;
        this.agent = agent;
        this.conversationId = conversationId;
    }

    public UUID getRunId() { return runId; }
    public UUID getAgentId() { return agentId; }
    public Agent getAgent() { return agent; }
    public String getConversationId() { return conversationId; }
    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
    public int getIterationCount() { return iterationCount; }
    public long getTotalTokensUsed() { return totalTokensUsed; }
    public List<String> getErrors() { return errors; }

    public void incrementIteration() { this.iterationCount++; }

    public void addTokensUsed(int inputTokens, int outputTokens) {
        this.totalTokensUsed += inputTokens + outputTokens;
    }

    public void addError(String error) { this.errors.add(error); }

    /**
     * Records a failure and, when it names a repair surface, the stable
     * code/surface the dashboard uses to offer the fixing action.
     */
    public void addError(Throwable failure) {
        if (failure == null) {
            return;
        }
        String message = failure.getMessage();
        this.errors.add(message != null ? message : "unknown error");
        if (failure instanceof ActionableFailure actionable) {
            if (this.errorCode == null) {
                this.errorCode = actionable.code();
            }
            if (this.errorSurface == null) {
                this.errorSurface = actionable.surface().name();
            }
        }
    }

    public String getErrorCode() { return errorCode; }
    public String getErrorSurface() { return errorSurface; }

    public String getLastAssistantResponse() { return lastAssistantResponse; }
    public void setLastAssistantResponse(String response) { this.lastAssistantResponse = response; }

    public List<LlmMessage> getPriorContext() { return priorContext; }
    public void setPriorContext(List<LlmMessage> priorContext) {
        this.priorContext = priorContext == null ? List.of() : List.copyOf(priorContext);
    }
}
