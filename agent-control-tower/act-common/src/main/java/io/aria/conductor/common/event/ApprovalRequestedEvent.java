package io.aria.conductor.common.event;

import lombok.AccessLevel;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

@Getter
public class ApprovalRequestedEvent extends ApplicationEvent {

    /**
     * ApprovalSource name of the persisted ask. Suppressed from Lombok so the accessor can be named
     * explicitly: {@code getSource()} belongs to the framework and must keep returning the publisher.
     */
    @Getter(AccessLevel.NONE)
    private final String source;

    private final UUID approvalId;
    private final UUID runId;
    private final UUID toolCallId;
    private final String approvalType;

    public ApprovalRequestedEvent(Object source, UUID approvalId, UUID runId, UUID toolCallId) {
        this(source, approvalId, runId, toolCallId, "TOOL_CALL");
    }

    public ApprovalRequestedEvent(Object source, UUID approvalId, UUID runId, UUID toolCallId, String approvalType) {
        this(source, approvalId, runId, toolCallId, approvalType, "LEGACY_GATE");
    }

    public ApprovalRequestedEvent(Object publisher, UUID approvalId, UUID runId, UUID toolCallId,
                                  String approvalType, String source) {
        super(publisher);
        this.approvalId = approvalId;
        this.runId = runId;
        this.toolCallId = toolCallId;
        this.approvalType = approvalType;
        this.source = source;
    }

    /** The persisted approval's source name ({@code LEGACY_GATE} or {@code ACP_PERMISSION}). */
    public String getApprovalSource() {
        return source;
    }
}
