package io.aria.conductor.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class KanbanItemAssigningEvent extends ApplicationEvent {

    private final String itemId;

    public KanbanItemAssigningEvent(Object source, String itemId) {
        super(source);
        this.itemId = itemId;
    }
}
