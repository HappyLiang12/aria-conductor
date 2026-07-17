package io.aria.conductor.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

@Getter
public class ApprovalRequestedEvent extends ApplicationEvent {

    private final UUID approvalId;
    private final UUID runId;
    private final UUID toolCallId;

    public ApprovalRequestedEvent(Object source, UUID approvalId, UUID runId, UUID toolCallId) {
        super(source);
        this.approvalId = approvalId;
        this.runId = runId;
        this.toolCallId = toolCallId;
    }
}
