package io.aria.conductor.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

@Getter
public class KnowledgeRetiredEvent extends ApplicationEvent {

    private final UUID knowledgeId;
    private final String name;

    public KnowledgeRetiredEvent(Object source, UUID knowledgeId, String name) {
        super(source);
        this.knowledgeId = knowledgeId;
        this.name = name;
    }
}
