package io.aria.conductor.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Published when the operator's answer or finalize signal is accepted for a
 * parked run (2026-10-05 waiting-input spec §5): wakes listeners tracking the
 * waiting-input loop.
 */
@Getter
public class RunInputReceivedEvent extends ApplicationEvent {

    private final UUID runId;

    public RunInputReceivedEvent(Object source, UUID runId) {
        super(source);
        this.runId = runId;
    }
}
