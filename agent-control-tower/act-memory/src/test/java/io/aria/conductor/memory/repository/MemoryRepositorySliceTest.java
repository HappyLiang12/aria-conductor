package io.aria.conductor.memory.repository;

import io.aria.conductor.memory.model.FactStatus;
import io.aria.conductor.memory.model.MemoryEntity;
import io.aria.conductor.memory.model.MemoryFact;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two memory-kernel invariants that must be enforced by the schema/queries
 * themselves, not only by a service:
 * <ul>
 *   <li>an entity canonical key is unique per kind (V81 {@code uq_memory_entity});</li>
 *   <li>only one ACTIVE current value is readable per entity/fact key, so a PENDING
 *       proposal can never shadow the accepted fact.</li>
 * </ul>
 */
class MemoryRepositorySliceTest extends MemorySliceTestSupport {

    @Autowired
    MemoryEntityRepository entities;

    @Autowired
    MemoryFactRepository facts;

    @Test
    void entityCanonicalKeyIsUniquePerKind() {
        entities.saveAndFlush(entity("PERSON", "operator"));

        assertThatThrownBy(() -> entities.saveAndFlush(entity("PERSON", "operator")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theSameCanonicalKeyIsAllowedUnderAnotherKind() {
        entities.saveAndFlush(entity("PERSON", "operator"));
        entities.saveAndFlush(entity("PROJECT", "operator"));

        assertThat(entities.count()).isEqualTo(2);
    }

    @Test
    void onlyOneActiveFactPerEntityAndKey() {
        MemoryEntity entity = entities.saveAndFlush(entity("PERSON", "operator"));

        facts.saveAndFlush(fact(entity.getId(), "timezone", FactStatus.ACTIVE));
        facts.saveAndFlush(fact(entity.getId(), "timezone", FactStatus.PENDING));

        assertThat(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(entity.getId()))
                .hasSize(1);
    }

    @Test
    void aRetiredFactIsNotACurrentValue() {
        MemoryEntity entity = entities.saveAndFlush(entity("PERSON", "operator"));
        MemoryFact retired = fact(entity.getId(), "timezone", FactStatus.ACTIVE);
        retired.setValidTo(Instant.now());
        facts.saveAndFlush(retired);

        assertThat(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(entity.getId()))
                .isEmpty();
    }
}
