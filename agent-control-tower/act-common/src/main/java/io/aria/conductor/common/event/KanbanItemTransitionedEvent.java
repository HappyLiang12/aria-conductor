package io.aria.conductor.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class KanbanItemTransitionedEvent extends ApplicationEvent {

    private final String itemId;
    private final String fromStatus;
    private final String toStatus;

    public KanbanItemTransitionedEvent(Object source, String itemId, String fromStatus, String toStatus) {
        super(source);
        this.itemId = itemId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
    }
}
