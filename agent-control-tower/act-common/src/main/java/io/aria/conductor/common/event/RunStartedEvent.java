package io.aria.conductor.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

@Getter
public class RunStartedEvent extends ApplicationEvent {

    private final UUID runId;
    private final UUID agentId;
    /**
     * When true, {@code RunKanbanAutoCreator} must not auto-create a kanban card
     * for this run: the orchestrating flow (kanban pickup) owns the card linkage
     * itself. Defaults to false for API-created runs.
     */
    private final boolean suppressAutoCard;

    public RunStartedEvent(Object source, UUID runId, UUID agentId) {
        this(source, runId, agentId, false);
    }

    public RunStartedEvent(Object source, UUID runId, UUID agentId, boolean suppressAutoCard) {
        super(source);
        this.runId = runId;
        this.agentId = agentId;
        this.suppressAutoCard = suppressAutoCard;
    }
}
