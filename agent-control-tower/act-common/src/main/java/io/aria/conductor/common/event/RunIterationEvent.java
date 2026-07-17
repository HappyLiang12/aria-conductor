package io.aria.conductor.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Published each time the agent loop engine completes an iteration.
 * Carries thinking content, tool call details, and skill context for dashboard observability.
 */
@Getter
public class RunIterationEvent extends ApplicationEvent {

    private final UUID runId;
    private final UUID agentId;
    private final int iteration;
    private final int maxIterations;
    private final String thinking;
    private final List<ToolCallDetail> toolCalls;
    private final List<String> skills;

    /** A single tool call executed during this iteration. */
    public record ToolCallDetail(String name, String arguments, String result) {}

    public RunIterationEvent(Object source, UUID runId, UUID agentId, int iteration, int maxIterations) {
        this(source, runId, agentId, iteration, maxIterations, null, List.of(), List.of());
    }

    public RunIterationEvent(Object source, UUID runId, UUID agentId, int iteration, int maxIterations,
                             String thinking, List<ToolCallDetail> toolCalls, List<String> skills) {
        super(source);
        this.runId = runId;
        this.agentId = agentId;
        this.iteration = iteration;
        this.maxIterations = maxIterations;
        this.thinking = thinking;
        this.toolCalls = toolCalls != null ? List.copyOf(toolCalls) : List.of();
        this.skills = skills != null ? List.copyOf(skills) : List.of();
    }
}
