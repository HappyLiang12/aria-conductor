package io.aria.conductor.common.event;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Published by {@code WorkflowAutoChainer} when a BA-kind step completes.
 * Consumed by {@code SpecReviewCoordinator} (act-knowledge) — the event keeps
 * the chainer free of a compile-time dependency on the coordinator module.
 */
public class BaStepCompletedEvent extends ApplicationEvent {

    private final UUID chainId;
    private final int baStepIndex;
    private final UUID baRunId;
    private final String finalOutput;

    public BaStepCompletedEvent(Object source, UUID chainId, int baStepIndex, UUID baRunId, String finalOutput) {
        super(source);
        this.chainId = chainId;
        this.baStepIndex = baStepIndex;
        this.baRunId = baRunId;
        this.finalOutput = finalOutput;
    }

    public UUID getChainId() {
        return chainId;
    }

    public int getBaStepIndex() {
        return baStepIndex;
    }

    public UUID getBaRunId() {
        return baRunId;
    }

    public String getFinalOutput() {
        return finalOutput;
    }
}
