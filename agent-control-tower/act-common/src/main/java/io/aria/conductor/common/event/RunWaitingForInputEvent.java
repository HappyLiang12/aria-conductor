package io.aria.conductor.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Published when a coordinated run parks waiting for operator input
 * (2026-10-05 waiting-input spec §2): carries the clarification question.
 */
@Getter
public class RunWaitingForInputEvent extends ApplicationEvent {

    private final UUID runId;
    private final String question;

    public RunWaitingForInputEvent(Object source, UUID runId) {
        this(source, runId, null);
    }

    public RunWaitingForInputEvent(Object source, UUID runId, String question) {
        super(source);
        this.runId = runId;
        this.question = question;
    }
}
