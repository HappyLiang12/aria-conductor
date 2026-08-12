package io.aria.conductor.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

@Getter
public class ApprovalRequestedEvent extends ApplicationEvent {

    private final UUID approvalId;
    private final UUID runId;
    private final UUID toolCallId;
    private final String approvalType;

    public ApprovalRequestedEvent(Object source, UUID approvalId, UUID runId, UUID toolCallId) {
        this(source, approvalId, runId, toolCallId, "TOOL_CALL");
    }

    public ApprovalRequestedEvent(Object source, UUID approvalId, UUID runId, UUID toolCallId, String approvalType) {
        super(source);
        this.approvalId = approvalId;
        this.runId = runId;
        this.toolCallId = toolCallId;
        this.approvalType = approvalType;
    }
}
