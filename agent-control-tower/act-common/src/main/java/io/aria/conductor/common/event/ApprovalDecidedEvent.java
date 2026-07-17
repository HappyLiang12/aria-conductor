package io.aria.conductor.common.event;

import io.aria.conductor.common.model.ApprovalStatus;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

@Getter
public class ApprovalDecidedEvent extends ApplicationEvent {

    private final UUID approvalId;
    private final ApprovalStatus decision;

    public ApprovalDecidedEvent(Object source, UUID approvalId, ApprovalStatus decision) {
        super(source);
        this.approvalId = approvalId;
        this.decision = decision;
    }
}
