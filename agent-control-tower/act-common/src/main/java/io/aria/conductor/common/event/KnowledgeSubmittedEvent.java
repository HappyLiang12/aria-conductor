package io.aria.conductor.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

@Getter
public class KnowledgeSubmittedEvent extends ApplicationEvent {

    private final UUID itemId;
    private final String type;
    private final String name;

    public KnowledgeSubmittedEvent(Object source, UUID itemId, String type, String name) {
        super(source);
        this.itemId = itemId;
        this.type = type;
        this.name = name;
    }
}
