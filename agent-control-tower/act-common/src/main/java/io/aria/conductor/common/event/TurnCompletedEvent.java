package io.aria.conductor.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Published when one loop turn of a coordinated run finishes (2026-10-05
 * waiting-input spec §5): carries the turn output, observed usage and the
 * model name the core actually served with.
 */
@Getter
public class TurnCompletedEvent extends ApplicationEvent {

    private final UUID runId;
    private final String finalOutput;
    private final Long inputTokens;
    private final Long outputTokens;
    private final String observedModel;

    public TurnCompletedEvent(Object source, UUID runId, String finalOutput) {
        this(source, runId, finalOutput, null, null, null);
    }

    public TurnCompletedEvent(Object source, UUID runId, String finalOutput, Long inputTokens, Long outputTokens, String observedModel) {
        super(source);
        this.runId = runId;
        this.finalOutput = finalOutput;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.observedModel = observedModel;
    }
}
