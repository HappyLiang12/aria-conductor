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

    public RunCompletedEvent(Object source, UUID runId, UUID agentId, RunStatus status) {
        this(source, runId, agentId, status, null);
    }

    public RunCompletedEvent(Object source, UUID runId, UUID agentId, RunStatus status, String finalOutput) {
        super(source);
        this.runId = runId;
        this.agentId = agentId;
        this.status = status;
        this.finalOutput = finalOutput;
    }
}
