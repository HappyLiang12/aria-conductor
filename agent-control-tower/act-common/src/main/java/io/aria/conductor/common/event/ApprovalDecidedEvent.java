package io.aria.conductor.common.event;

import io.aria.conductor.common.model.ApprovalStatus;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

@Getter
public class ApprovalDecidedEvent extends ApplicationEvent {

    private final UUID approvalId;
    private final ApprovalStatus decision;
    private final String approvalType;

    public ApprovalDecidedEvent(Object source, UUID approvalId, ApprovalStatus decision) {
        this(source, approvalId, decision, "TOOL_CALL");
    }

    public ApprovalDecidedEvent(Object source, UUID approvalId, ApprovalStatus decision, String approvalType) {
        super(source);
        this.approvalId = approvalId;
        this.decision = decision;
        this.approvalType = approvalType;
    }
}
