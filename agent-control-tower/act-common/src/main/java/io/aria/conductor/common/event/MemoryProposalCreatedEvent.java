package io.aria.conductor.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * A run created a PENDING memory proposal that an operator has to decide on. Today the producer
 * is {@code MemoryCommandService.createEntity}: an EXTRACTION actor proposes an entity (and its
 * PENDING facts attach to it), because a brand-new operator has no entity to hang a fact on.
 * <p>
 * {@code proposalId} is the id the decision is addressed to, {@code subjectType} is the
 * {@code MemorySubjectType} name ({@code ENTITY} today, {@code FACT} when a fact proposal also
 * notifies). For an ENTITY proposal {@code factKey} carries the entity's canonical key and
 * {@code valueText} its display name — the same two fields a fact proposal uses, so one card
 * renderer serves both. {@code runId} addresses the run whose stream renders the card; the event
 * is published only when the actor carries one.
 */
@Getter
public class MemoryProposalCreatedEvent extends ApplicationEvent {

    private final UUID proposalId;
    private final String subjectType;
    private final UUID entityId;
    private final String factKey;
    private final String valueText;
    private final String sensitivity;
    private final UUID runId;

    public MemoryProposalCreatedEvent(Object source, UUID proposalId, String subjectType, UUID entityId,
                                      String factKey, String valueText, String sensitivity, UUID runId) {
        super(source);
        this.proposalId = proposalId;
        this.subjectType = subjectType;
        this.entityId = entityId;
        this.factKey = factKey;
        this.valueText = valueText;
        this.sensitivity = sensitivity;
        this.runId = runId;
    }
}
