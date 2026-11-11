package io.aria.conductor.memory.repository;

import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.memory.model.FactStatus;
import io.aria.conductor.memory.model.MemoryAction;
import io.aria.conductor.memory.model.MemoryActorType;
import io.aria.conductor.memory.model.MemoryAssertion;
import io.aria.conductor.memory.model.MemoryEntity;
import io.aria.conductor.memory.model.MemoryFact;
import io.aria.conductor.memory.model.MemoryKnowledgeLink;
import io.aria.conductor.memory.model.MemoryLinkStatus;
import io.aria.conductor.memory.model.MemoryOrigin;
import io.aria.conductor.memory.model.MemoryRelation;
import io.aria.conductor.memory.model.MemorySubjectType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Persist/reload coverage for every memory table, not only entities and facts: the
 * ontology is only usable if a relation, an assertion and a knowledge link come back
 * with the same enum constants they were written with.
 * <p>
 * The declared mapping is {@code @Enumerated(EnumType.STRING)}, so each round trip is
 * paired with a raw read of the stored column: the assertions fail if someone drops the
 * annotation and Hibernate falls back to ordinal storage (which would silently survive a
 * reload but break V81's VARCHAR columns and every hand-written SQL reader).
 */
class MemoryKernelRoundTripSliceTest extends MemorySliceTestSupport {

    @Autowired
    MemoryEntityRepository entities;

    @Autowired
    MemoryFactRepository facts;

    @Autowired
    MemoryRelationRepository relations;

    @Autowired
    MemoryAssertionRepository assertions;

    @Autowired
    MemoryKnowledgeLinkRepository links;

    private MemoryFact proposal(UUID entityId, String proposalHash) {
        MemoryFact fact = fact(entityId, "editor", FactStatus.PENDING);
        fact.setProposalHash(proposalHash);
        return fact;
    }

    @Test
    void aRelationRoundTripsItsEnumsAsStrings() {
        MemoryEntity a = entities.saveAndFlush(entity("PERSON", "operator"));
        MemoryEntity b = entities.saveAndFlush(entity("PROJECT", "conductor"));
        String hash = "rt-relation-" + UUID.randomUUID();
        MemoryRelation saved = relations.saveAndFlush(MemoryRelation.builder()
                .fromEntityId(a.getId())
                .predicate("OWNS")
                .toEntityId(b.getId())
                .status(FactStatus.ACTIVE)
                .origin(MemoryOrigin.OPERATOR)
                .sensitivity(Sensitivity.CONFIDENTIAL)
                .confidence(new BigDecimal("0.90"))
                .proposalHash(hash)
                .validFrom(Instant.now().truncatedTo(ChronoUnit.MILLIS))
                .build());
        UUID id = saved.getId();
        flushAndClear();

        MemoryRelation reloaded = relations.findById(id).orElseThrow();

        assertThat(reloaded.getStatus()).isSameAs(FactStatus.ACTIVE);
        assertThat(reloaded.getOrigin()).isSameAs(MemoryOrigin.OPERATOR);
        assertThat(reloaded.getSensitivity()).isSameAs(Sensitivity.CONFIDENTIAL);
        assertThat(reloaded.getProposalHash()).isEqualTo(hash);
        assertThat(nativeColumn("select status from memory_relations where proposal_hash = ?1", hash))
                .as("enums are stored by name, not by ordinal")
                .isEqualTo("ACTIVE");
        assertThat(nativeColumn("select origin from memory_relations where proposal_hash = ?1", hash))
                .isEqualTo("OPERATOR");
    }

    @Test
    void aRelationProposalHashIsUniqueWhenPresentAndMayBeAbsentOnManyRows() {
        MemoryEntity a = entities.saveAndFlush(entity("PERSON", "operator"));
        MemoryEntity b = entities.saveAndFlush(entity("PROJECT", "conductor"));
        relations.saveAndFlush(relation(a.getId(), "OWNS", b.getId(), FactStatus.ACTIVE, null));
        relations.saveAndFlush(relation(a.getId(), "OWNS", b.getId(), FactStatus.ACTIVE, null));
        assertThat(relations.count()).as("operator/run writes carry no proposal hash").isEqualTo(2);

        String hash = "dup-" + UUID.randomUUID();
        relations.saveAndFlush(relation(a.getId(), "WORKS_ON", b.getId(), FactStatus.ACTIVE, hash));
        assertThatThrownBy(() -> relations.saveAndFlush(
                relation(a.getId(), "WORKS_ON", b.getId(), FactStatus.ACTIVE, hash)))
                .as("a retried extraction cannot duplicate its row")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * The database level of the duplicate-proposal rule: the service's probe can be passed
     * by two concurrent extractions, so {@code uq_memory_fact_proposal} must reject the
     * second row. A row without a hash (operator/run writes) is not constrained.
     */
    @Test
    void aFactProposalHashIsUniqueWhenPresentAndMayBeAbsentOnManyRows() {
        MemoryEntity entity = entities.saveAndFlush(entity("PERSON", "operator"));
        facts.saveAndFlush(proposal(entity.getId(), null));
        facts.saveAndFlush(proposal(entity.getId(), null));
        assertThat(facts.count()).as("operator/run writes carry no proposal hash").isEqualTo(2);

        String hash = "dup-" + UUID.randomUUID();
        facts.saveAndFlush(proposal(entity.getId(), hash));
        assertThatThrownBy(() -> facts.saveAndFlush(proposal(entity.getId(), hash)))
                .as("a retried extraction cannot duplicate its proposal")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anAssertionRoundTripsItsEnumsAsStrings() {
        UUID subjectId = UUID.randomUUID();
        MemoryAssertion saved = assertions.saveAndFlush(MemoryAssertion.builder()
                .subjectType(MemorySubjectType.RELATION)
                .subjectId(subjectId)
                .action(MemoryAction.SUPERSEDE)
                .actorType(MemoryActorType.OPERATOR)
                .actorRef("operator")
                .beforeJson("{\"status\":\"ACTIVE\"}")
                .afterJson("{\"status\":\"RETIRED\"}")
                .sourceRef("run-" + subjectId)
                .build());
        UUID id = saved.getId();
        flushAndClear();

        MemoryAssertion reloaded = assertions.findById(id).orElseThrow();

        assertThat(reloaded.getSubjectType()).isSameAs(MemorySubjectType.RELATION);
        assertThat(reloaded.getAction()).isSameAs(MemoryAction.SUPERSEDE);
        assertThat(reloaded.getActorType()).isSameAs(MemoryActorType.OPERATOR);
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(nativeColumn("select subject_type from memory_assertions where id = ?1", id.toString()))
                .isEqualTo("RELATION");
        assertThat(nativeColumn("select action from memory_assertions where id = ?1", id.toString()))
                .isEqualTo("SUPERSEDE");
    }

    @Test
    void aKnowledgeLinkRoundTripsItsStatusAndDecision() {
        MemoryEntity entity = entities.saveAndFlush(entity("PERSON", "operator"));
        UUID knowledgeItemId = UUID.randomUUID();
        MemoryKnowledgeLink saved = links.saveAndFlush(MemoryKnowledgeLink.builder()
                .entityId(entity.getId())
                .knowledgeItemId(knowledgeItemId)
                .status(MemoryLinkStatus.ACTIVE)
                .decidedAt(Instant.now())
                .decidedBy("operator")
                .build());
        UUID id = saved.getId();
        flushAndClear();

        MemoryKnowledgeLink reloaded = links.findById(id).orElseThrow();

        assertThat(reloaded.getStatus()).isSameAs(MemoryLinkStatus.ACTIVE);
        assertThat(reloaded.getKnowledgeItemId()).isEqualTo(knowledgeItemId);
        assertThat(reloaded.getDecidedBy()).isEqualTo("operator");
        assertThat(nativeColumn("select status from memory_knowledge_links where entity_id = ?1",
                entity.getId().toString()))
                .isEqualTo("ACTIVE");
    }
}
