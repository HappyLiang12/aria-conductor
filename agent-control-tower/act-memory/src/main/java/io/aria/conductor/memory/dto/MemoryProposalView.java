package io.aria.conductor.memory.dto;

import io.aria.conductor.memory.model.MemoryFact;
import io.aria.conductor.memory.model.MemoryRelation;
import io.aria.conductor.memory.model.MemorySubjectType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Read view of a proposal — a PENDING fact or relation awaiting an operator decision.
 * {@code subjectType} tells the REST and MCP surfaces which decision endpoint owns it, and
 * {@code id} is the subject id the decision is addressed to (spec §9).
 */
public record MemoryProposalView(
        String id,
        String subjectType,
        String entityId,
        String factKey,
        String valueText,
        String predicate,
        String toEntityId,
        String status,
        String origin,
        String sensitivity,
        BigDecimal confidence,
        Instant createdAt,
        Instant validFrom) {

    public static MemoryProposalView fromFact(MemoryFact fact) {
        return new MemoryProposalView(
                idString(fact.getId()),
                MemorySubjectType.FACT.name(),
                idString(fact.getEntityId()),
                fact.getFactKey(),
                fact.getValueText(),
                null,
                null,
                nameOf(fact.getStatus()),
                nameOf(fact.getOrigin()),
                nameOf(fact.getSensitivity()),
                fact.getConfidence(),
                fact.getCreatedAt(),
                fact.getValidFrom());
    }

    public static MemoryProposalView fromRelation(MemoryRelation relation) {
        return new MemoryProposalView(
                idString(relation.getId()),
                MemorySubjectType.RELATION.name(),
                idString(relation.getFromEntityId()),
                null,
                null,
                relation.getPredicate(),
                idString(relation.getToEntityId()),
                nameOf(relation.getStatus()),
                nameOf(relation.getOrigin()),
                nameOf(relation.getSensitivity()),
                relation.getConfidence(),
                relation.getCreatedAt(),
                relation.getValidFrom());
    }

    private static String idString(java.util.UUID id) {
        return id == null ? null : id.toString();
    }

    private static String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
