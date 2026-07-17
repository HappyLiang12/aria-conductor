package io.aria.conductor.common.event;

import io.aria.conductor.common.model.WorkflowChain;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Fired when a workflow chain advances to a new step or completes.
 */
@Getter
public class WorkflowAdvancedEvent extends ApplicationEvent {

    private final UUID workflowId;
    private final String workflowName;
    private final int completedStep;
    private final int nextStep;
    private final WorkflowChain.Status chainStatus;

    public WorkflowAdvancedEvent(Object source, UUID workflowId, String workflowName,
                                 int completedStep, int nextStep, WorkflowChain.Status chainStatus) {
        super(source);
        this.workflowId = workflowId;
        this.workflowName = workflowName;
        this.completedStep = completedStep;
        this.nextStep = nextStep;
        this.chainStatus = chainStatus;
    }
}
