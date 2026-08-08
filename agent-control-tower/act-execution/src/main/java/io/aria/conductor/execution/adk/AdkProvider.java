package io.aria.conductor.execution.adk;

import io.aria.conductor.common.model.Agent;
import io.aria.conductor.execution.llm.LlmMessage;
import io.aria.conductor.execution.llm.LlmResponse;
import io.aria.conductor.execution.pipeline.Action;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Strategy interface for ADK (Agent Development Kit) providers.
 *
 * <p>Each implementation wraps a specific agent runtime (LangChain ADK subprocess,
 * LangChain Python process, etc.) behind a uniform contract so that
 * {@link io.aria.conductor.execution.engine.AgentLoopEngine} can invoke any
 * provider without knowing the concrete type.
 */
public interface AdkProvider {

    /** Unique identifier for this provider (e.g. {@code "langchain"}). */
    String providerId();

    /**
     * Execute a single LLM call through the ADK runtime.
     *
     * @param agentId  the agent's UUID
     * @param messages full conversation history (system + user + assistant turns)
     * @param tools    OpenAI function-calling tool definitions
     * @return the LLM response, potentially containing tool calls
     */
    LlmResponse call(UUID agentId, List<LlmMessage> messages, List<Map<String, Object>> tools);

    /**
     * Parse tool calls from an LLM response into executable {@link Action}s.
     *
     * @param response the raw LLM response
     * @return list of actions (empty if no tool calls)
     */
    List<Action> parseActionsFromResponse(LlmResponse response);

    /**
     * Prepare the agent runtime (e.g. spawn a subprocess).
     * Called before the first {@link #call} for a given agent.
     */
    void prepareAgent(UUID agentId, Agent agent);

    /** Check whether the agent runtime is healthy and reachable. */
    boolean isHealthy(UUID agentId);

    /**
     * Service-level health probe that needs no agent context (used by the
     * provider inventory / health API, {@code GET /api/v1/adk/providers/{id}/health}).
     *
     * <p>Unlike {@link #isHealthy(UUID)} (instance-scoped, requires an agentId),
     * this probes the provider's underlying runtime service (ADK host:port for
     * langchain, OpenSandbox server reachability for opencode). Defaults to
     * {@code true} for providers without a meaningful service-level probe.
     */
    default boolean isServiceHealthy() {
        return true;
    }

    /** Shut down the runtime for a specific agent. */
    void shutdownAgent(UUID agentId);

    /** Shut down all runtimes managed by this provider. */
    void shutdownAll();

    /**
     * Whether this provider executes end-to-end tasks (agent semantics).
     *
     * <p>Providers that return {@code true} handle the whole run internally
     * (their own tool loop) and are invoked via {@link #executeTask} instead
     * of the turn-level {@link #call}. Default {@code false}.
     */
    default boolean supportsTaskExecution() {
        return false;
    }

    /**
     * Task-level execution: hand the whole run to the provider's internal loop.
     *
     * <p>Only invoked when {@link #supportsTaskExecution()} returns {@code true}.
     *
     * @param agent      the agent being executed
     * @param runId      unique identifier of this run
     * @param taskPrompt the task prompt handed to the provider
     * @param context    task-level constraints (round limit, timeout, audit sink)
     * @return the aggregated task result
     * @throws TaskExecutionException if the task fails (sandbox unavailable, timeout, ...)
     */
    default TaskResult executeTask(Agent agent, UUID runId, String taskPrompt, TaskContext context) {
        throw new UnsupportedOperationException(
                "Provider " + providerId() + " does not support task execution");
    }

    /**
     * Abort an in-flight task (budget exceeded / user cancel).
     *
     * <p>Default no-op — turn-level providers do not manage long-running tasks.
     *
     * @param runId unique identifier of the run to abort
     */
    default void abortTask(UUID runId) {
    }
}
