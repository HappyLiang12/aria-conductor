package io.aria.conductor.common.event;

import io.aria.conductor.common.model.RunStatus;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

@Getter
public class RunCompletedEvent extends ApplicationEvent {

    private final UUID runId;
    private final UUID agentId;
    private final RunStatus status;
    private final String finalOutput;
    /** Operator-facing failure reason; null when the run did not fail with one. */
    private final String errorMessage;
    /** Stable identity and repair surface of the failure (null when it has none). */
    private final String errorCode;
    private final String errorSurface;

    public RunCompletedEvent(Object source, UUID runId, UUID agentId, RunStatus status) {
        this(source, runId, agentId, status, null, null);
    }

    public RunCompletedEvent(Object source, UUID runId, UUID agentId, RunStatus status, String finalOutput) {
        this(source, runId, agentId, status, finalOutput, null);
    }

    public RunCompletedEvent(Object source, UUID runId, UUID agentId, RunStatus status,
                             String finalOutput, String errorMessage) {
        this(source, runId, agentId, status, finalOutput, errorMessage, null, null);
    }

    public RunCompletedEvent(Object source, UUID runId, UUID agentId, RunStatus status,
                             String finalOutput, String errorMessage,
                             String errorCode, String errorSurface) {
        super(source);
        this.runId = runId;
        this.agentId = agentId;
        this.status = status;
        this.finalOutput = finalOutput;
        this.errorMessage = errorMessage;
        this.errorCode = errorCode;
        this.errorSurface = errorSurface;
    }
}
