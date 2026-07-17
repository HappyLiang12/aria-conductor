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

    /** Shut down the runtime for a specific agent. */
    void shutdownAgent(UUID agentId);

    /** Shut down all runtimes managed by this provider. */
    void shutdownAll();
}
