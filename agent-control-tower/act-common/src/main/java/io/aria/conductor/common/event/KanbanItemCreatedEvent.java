package io.aria.conductor.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class KanbanItemCreatedEvent extends ApplicationEvent {

    private final String itemId;
    private final String title;
    private final String priority;

    public KanbanItemCreatedEvent(Object source, String itemId, String title, String priority) {
        super(source);
        this.itemId = itemId;
        this.title = title;
        this.priority = priority;
    }
}
