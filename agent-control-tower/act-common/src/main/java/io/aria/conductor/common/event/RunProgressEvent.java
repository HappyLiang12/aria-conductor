package io.aria.conductor.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Streamed progress fragment published by the OpenCode progress pump. Persisted to
 * run_progress_events and broadcast over WS.
 */
@Getter
public class RunProgressEvent extends ApplicationEvent {

    /**
     * Origin of the progress fragment.
     *
     * <p>{@link #THINKING} is what the model works out; {@link #ANSWER} is what it
     * says back (issue #103: both used to be THINKING, so the Live Activity Stream
     * showed the reply under the think affordance). Every kind here is assistant
     * activity — the operator's own prompt is never a progress fragment.
     */
    public enum Kind { THINKING, ANSWER, TOOL_CALL, TOOL_RESULT, STATUS, ERROR }

    private final UUID runId;
    private final UUID agentId;
    private final int iteration;
    private final Kind kind;
    private final String content;
    private final String toolName;
    /** Monotonic part counter for client-side dedupe (watermark resets). */
    private final long seq;

    public RunProgressEvent(Object source, UUID runId, UUID agentId, int iteration,
                            Kind kind, String content, String toolName, long seq) {
        super(source);
        this.runId = runId;
        this.agentId = agentId;
        this.iteration = iteration;
        this.kind = kind;
        this.content = content != null ? content : "";
        this.toolName = toolName;
        this.seq = seq;
    }
}
