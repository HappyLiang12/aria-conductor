package io.aria.conductor.memory.repository;

import io.aria.conductor.memory.model.EntityStatus;
import io.aria.conductor.memory.model.FactStatus;
import io.aria.conductor.memory.model.MemoryAssertion;
import io.aria.conductor.memory.model.MemoryEntity;
import io.aria.conductor.memory.model.MemoryFact;
import io.aria.conductor.memory.model.MemoryKnowledgeLink;
import io.aria.conductor.memory.model.MemoryLinkStatus;
import io.aria.conductor.memory.model.MemoryRelation;
import io.aria.conductor.memory.model.MemorySubjectType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The derived queries the recall and entity-detail lanes call. Lanes B (recall, backfill)
 * and D (REST detail endpoint) may only add services and DTOs, so every read they need
 * must exist here — and must filter the way the spec says:
 * <ul>
 *   <li>recall sees ACTIVE entities only, by canonical key or by kind;</li>
 *   <li>recall sees ACTIVE facts already valid at the request instant, for many entities;</li>
 *   <li>detail sees the whole fact history newest-first, both relation directions,
 *       every knowledge link, and the audit trail of one subject oldest-first.</li>
 * </ul>
 */
class MemoryRecallQuerySliceTest extends MemorySliceTestSupport {

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

    @Test
    void recallFindsActiveEntitiesByCanonicalKeyAndByKind() {
        MemoryEntity operator = entities.saveAndFlush(entity("PERSON", "operator"));
        MemoryEntity retiredRepo = entity("REPO", "aria-conductor");
        retiredRepo.setStatus(EntityStatus.RETIRED);
        entities.saveAndFlush(retiredRepo);
        MemoryEntity activeRepo = entities.saveAndFlush(entity("REPO", "aria-conductor-w0b"));
        flushAndClear();

        List<MemoryEntity> byKey = entities.findByCanonicalKeyInAndStatus(
                List.of("operator", "aria-conductor"), EntityStatus.ACTIVE);
        List<MemoryEntity> byKind = entities.findByKindAndStatus("REPO", EntityStatus.ACTIVE);

        assertThat(byKey).extracting(MemoryEntity::getId).containsExactly(operator.getId());
        assertThat(byKind).extracting(MemoryEntity::getId).containsExactly(activeRepo.getId());
    }

    @Test
    void recallLoadsOnlyActiveFactsValidAtTheQueryInstantForManyEntities() {
        MemoryEntity operator = entities.saveAndFlush(entity("PERSON", "operator"));
        MemoryEntity repo = entities.saveAndFlush(entity("REPO", "aria-conductor-w0b"));
        Instant now = Instant.now();

        MemoryFact current = fact(operator.getId(), "timezone", FactStatus.ACTIVE);
        current.setValidFrom(now.minus(1, ChronoUnit.HOURS));
        facts.saveAndFlush(current);

        MemoryFact future = fact(repo.getId(), "branch", FactStatus.ACTIVE);
        future.setValidFrom(now.plus(1, ChronoUnit.HOURS));
        facts.saveAndFlush(future);

        MemoryFact closed = fact(repo.getId(), "editor", FactStatus.ACTIVE);
        closed.setValidFrom(now.minus(2, ChronoUnit.HOURS));
        closed.setValidTo(now.minus(1, ChronoUnit.HOURS));
        facts.saveAndFlush(closed);

        MemoryFact pending = fact(repo.getId(), "host", FactStatus.PENDING);
        pending.setValidFrom(now.minus(1, ChronoUnit.HOURS));
        facts.saveAndFlush(pending);
        flushAndClear();

        List<MemoryFact> rows = facts.findByEntityIdInAndStatusAndValidFromLessThanEqualAndValidToIsNull(
                List.of(operator.getId(), repo.getId()), FactStatus.ACTIVE, now);

        assertThat(rows).extracting(MemoryFact::getFactKey).containsExactly("timezone");
    }

    @Test
    void entityDetailListsEveryFactVersionNewestValidFromFirst() {
        MemoryEntity entity = entities.saveAndFlush(entity("PERSON", "operator"));
        MemoryEntity other = entities.saveAndFlush(entity("PERSON", "reviewer"));
        Instant now = Instant.now();

        MemoryFact superseded = fact(entity.getId(), "timezone", FactStatus.ACTIVE);
        superseded.setValidFrom(now.minus(3, ChronoUnit.HOURS));
        superseded.setValidTo(now.minus(2, ChronoUnit.HOURS));
        facts.saveAndFlush(superseded);
        MemoryFact current = fact(entity.getId(), "timezone", FactStatus.ACTIVE);
        current.setValidFrom(now.minus(1, ChronoUnit.HOURS));
        facts.saveAndFlush(current);
        MemoryFact pending = fact(entity.getId(), "editor", FactStatus.PENDING);
        pending.setValidFrom(now.minus(2, ChronoUnit.HOURS));
        facts.saveAndFlush(pending);
        facts.saveAndFlush(fact(other.getId(), "timezone", FactStatus.ACTIVE));
        flushAndClear();

        List<MemoryFact> rows = facts.findByEntityIdOrderByValidFromDesc(entity.getId());

        assertThat(rows).hasSize(3);
        assertThat(rows).isSortedAccordingTo(
                Comparator.comparing(MemoryFact::getValidFrom).reversed());
        assertThat(rows).allMatch(row -> entity.getId().equals(row.getEntityId()));
        assertThat(rows.get(0).getFactKey()).as("the current value leads the history").isEqualTo("timezone");
        assertThat(rows.get(0).getValidTo()).isNull();
    }

    @Test
    void entityDetailListsRelationsByFromAndToForOneStatus() {
        MemoryEntity a = entities.saveAndFlush(entity("PERSON", "operator"));
        MemoryEntity b = entities.saveAndFlush(entity("PROJECT", "conductor"));
        MemoryEntity c = entities.saveAndFlush(entity("REPO", "aria-conductor-w0b"));
        relations.saveAndFlush(relation(a.getId(), "OWNS", b.getId(), FactStatus.ACTIVE, null));
        relations.saveAndFlush(relation(a.getId(), "WORKS_ON", c.getId(), FactStatus.PENDING, null));
        relations.saveAndFlush(relation(c.getId(), "DEPENDS_ON", a.getId(), FactStatus.ACTIVE, null));
        flushAndClear();

        List<MemoryRelation> outgoing = relations.findByFromEntityIdAndStatus(a.getId(), FactStatus.ACTIVE);
        List<MemoryRelation> incoming = relations.findByToEntityIdAndStatus(a.getId(), FactStatus.ACTIVE);

        assertThat(outgoing).extracting(MemoryRelation::getToEntityId).containsExactly(b.getId());
        assertThat(incoming).extracting(MemoryRelation::getFromEntityId).containsExactly(c.getId());
    }

    @Test
    void entityDetailListsKnowledgeLinksForAnEntity() {
        MemoryEntity entity = entities.saveAndFlush(entity("PERSON", "operator"));
        MemoryEntity other = entities.saveAndFlush(entity("PERSON", "reviewer"));
        links.saveAndFlush(link(entity.getId(), MemoryLinkStatus.ACTIVE));
        links.saveAndFlush(link(entity.getId(), MemoryLinkStatus.PROPOSED));
        links.saveAndFlush(link(other.getId(), MemoryLinkStatus.ACTIVE));
        flushAndClear();

        assertThat(links.findByEntityId(entity.getId()))
                .extracting(MemoryKnowledgeLink::getEntityId)
                .containsOnly(entity.getId());
    }

    @Test
    void entityDetailListsAssertionsForASubjectInCreationOrder() {
        UUID subjectId = UUID.randomUUID();
        // V81 stores created_at as TIMESTAMP (microsecond precision).
        Instant base = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        assertions.saveAndFlush(assertion(MemorySubjectType.ENTITY, subjectId, base.plusSeconds(30)));
        assertions.saveAndFlush(assertion(MemorySubjectType.ENTITY, subjectId, base));
        assertions.saveAndFlush(assertion(MemorySubjectType.ENTITY, subjectId, base.plusSeconds(60)));
        assertions.saveAndFlush(assertion(MemorySubjectType.FACT, subjectId, base));
        assertions.saveAndFlush(assertion(MemorySubjectType.ENTITY, UUID.randomUUID(), base));
        flushAndClear();

        List<MemoryAssertion> rows = assertions
                .findBySubjectTypeAndSubjectIdOrderByCreatedAtAsc(MemorySubjectType.ENTITY, subjectId);

        assertThat(rows).extracting(MemoryAssertion::getCreatedAt)
                .containsExactly(base, base.plusSeconds(30), base.plusSeconds(60));
    }
}
