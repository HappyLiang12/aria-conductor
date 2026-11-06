package io.aria.conductor.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

@Getter
public class AgentCreatedEvent extends ApplicationEvent {

    private final UUID agentId;
    private final String name;
    private final String type;

    public AgentCreatedEvent(Object source, UUID agentId, String name, String type) {
        super(source);
        this.agentId = agentId;
        this.name = name;
        this.type = type;
    }
}
