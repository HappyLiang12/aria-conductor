package io.aria.conductor.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * S8: in-memory streaming progress event published by the OpenCode progress pump
 * (and the langchain SSE forwarding fallback). Transient by design — NEVER
 * persisted to the database (no trajectory write amplification).
 */
@Getter
public class RunProgressEvent extends ApplicationEvent {

    /** Origin of the progress fragment. */
    public enum Kind { THINKING, TOOL_CALL, TOOL_RESULT, STATUS, ERROR }

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
