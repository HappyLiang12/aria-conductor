package io.aria.conductor.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

@Getter
public class RunStartedEvent extends ApplicationEvent {

    private final UUID runId;
    private final UUID agentId;

    public RunStartedEvent(Object source, UUID runId, UUID agentId) {
        super(source);
        this.runId = runId;
        this.agentId = agentId;
    }
}
