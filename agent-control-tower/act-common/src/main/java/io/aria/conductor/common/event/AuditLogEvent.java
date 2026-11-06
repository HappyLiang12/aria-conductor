package io.aria.conductor.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class AuditLogEvent extends ApplicationEvent {

    private final String eventType;
    private final String resourceType;
    private final String resourceId;
    private final String action;
    private final String details;
    private final String conversationId;

    public AuditLogEvent(Object source, String eventType, String resourceType,
                         String resourceId, String action, String details,
                         String conversationId) {
        super(source);
        this.eventType = eventType;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.action = action;
        this.details = details;
        this.conversationId = conversationId;
    }
}
