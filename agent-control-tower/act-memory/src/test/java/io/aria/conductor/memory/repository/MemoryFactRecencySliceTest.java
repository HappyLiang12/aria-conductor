package io.aria.conductor.memory.repository;

import io.aria.conductor.memory.model.FactStatus;
import io.aria.conductor.memory.model.MemoryEntity;
import io.aria.conductor.memory.model.MemoryFact;
import io.aria.conductor.memory.model.MemoryRelation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recency must be defined for a row that has never been updated.
 * <p>
 * {@code findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc} is the fact read of
 * the recall path, and {@code @PreUpdate} was the only writer of {@code updated_at}: a
 * freshly inserted fact was left NULL, so "newest first" fell back to whatever order the
 * database returned. The insert stamp is the fix; {@code MemoryEntity} and
 * {@code MemoryRelation} stamp on insert too, for the same reason.
 */
class MemoryFactRecencySliceTest extends MemorySliceTestSupport {

    @Autowired
    MemoryEntityRepository entities;

    @Autowired
    MemoryFactRepository facts;

    @Autowired
    MemoryRelationRepository relations;

    @Test
    void aFreshlyInsertedRowIsStampedWithUpdatedAt() {
        MemoryEntity entity = entities.saveAndFlush(entity("PERSON", "operator"));
        MemoryRelation relation = relations.saveAndFlush(
                relation(entity.getId(), "OWNS", entity.getId(), FactStatus.ACTIVE, null));
        MemoryFact fact = facts.saveAndFlush(fact(entity.getId(), "timezone", FactStatus.ACTIVE));

        assertThat(entity.getUpdatedAt()).as("entity insert").isNotNull();
        assertThat(relation.getUpdatedAt()).as("relation insert").isNotNull();
        assertThat(fact.getUpdatedAt()).as("fact insert").isNotNull();
    }

    @Test
    void threeFactsInsertedInOrderComeBackNewestFirst() throws InterruptedException {
        MemoryEntity entity = entities.saveAndFlush(entity("PERSON", "operator"));
        facts.saveAndFlush(fact(entity.getId(), "k1", FactStatus.ACTIVE));
        pause();
        facts.saveAndFlush(fact(entity.getId(), "k2", FactStatus.ACTIVE));
        pause();
        facts.saveAndFlush(fact(entity.getId(), "k3", FactStatus.ACTIVE));
        flushAndClear();

        List<MemoryFact> rows = facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(entity.getId());

        assertThat(rows).extracting(MemoryFact::getFactKey).containsExactly("k3", "k2", "k1");
    }

    private void pause() throws InterruptedException {
        Thread.sleep(5);
    }
}
