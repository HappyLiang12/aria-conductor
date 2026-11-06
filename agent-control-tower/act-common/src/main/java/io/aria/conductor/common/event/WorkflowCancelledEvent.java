package io.aria.conductor.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Fired when a workflow chain is cancelled.
 */
@Getter
public class WorkflowCancelledEvent extends ApplicationEvent {

    private final UUID workflowId;
    private final String workflowName;

    public WorkflowCancelledEvent(Object source, UUID workflowId, String workflowName) {
        super(source);
        this.workflowId = workflowId;
        this.workflowName = workflowName;
    }
}
