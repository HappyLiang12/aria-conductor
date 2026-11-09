package io.aria.conductor.memory.repository;

import io.aria.conductor.memory.MemoryJpaSliceConfig;
import io.aria.conductor.memory.model.FactStatus;
import io.aria.conductor.memory.model.MemoryAction;
import io.aria.conductor.memory.model.MemoryActorType;
import io.aria.conductor.memory.model.MemoryAssertion;
import io.aria.conductor.memory.model.MemoryEntity;
import io.aria.conductor.memory.model.MemoryFact;
import io.aria.conductor.memory.model.MemoryKnowledgeLink;
import io.aria.conductor.memory.model.MemoryLinkStatus;
import io.aria.conductor.memory.model.MemoryRelation;
import io.aria.conductor.memory.model.MemorySubjectType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Bootstrap and fixtures shared by the act-memory repository slices.
 * <p>
 * act-memory carries its own JPA slice config (it has no compile or test dependency on
 * act-test-support), so every slice extends this class instead of repeating
 * {@code @DataJpaTest} + {@link MemoryJpaSliceConfig}. The name deliberately does not
 * match surefire's test patterns.
 */
@DataJpaTest
@ContextConfiguration(classes = MemoryJpaSliceConfig.class)
abstract class MemorySliceTestSupport {

    @PersistenceContext
    EntityManager entityManager;

    /** Flush pending writes and clear the persistence context so reads hit the database. */
    void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    /** Read one column straight from the table, bypassing the enum mapping under test. */
    String nativeColumn(String sql, String parameter) {
        return (String) entityManager.createNativeQuery(sql).setParameter(1, parameter).getSingleResult();
    }

    MemoryEntity entity(String kind, String canonicalKey) {
        return MemoryEntity.builder()
                .kind(kind)
                .canonicalKey(canonicalKey)
                .displayName(canonicalKey)
                .build();
    }

    MemoryFact fact(UUID entityId, String key, FactStatus status) {
        return MemoryFact.builder()
                .entityId(entityId)
                .factKey(key)
                .valueText("UTC")
                .valueType("STRING")
                .status(status)
                .validFrom(Instant.now())
                .build();
    }

    MemoryRelation relation(UUID from, String predicate, UUID to, FactStatus status, String proposalHash) {
        return MemoryRelation.builder()
                .fromEntityId(from)
                .predicate(predicate)
                .toEntityId(to)
                .status(status)
                .proposalHash(proposalHash)
                .validFrom(Instant.now().truncatedTo(ChronoUnit.MILLIS))
                .build();
    }

    MemoryAssertion assertion(MemorySubjectType subjectType, UUID subjectId, Instant createdAt) {
        return MemoryAssertion.builder()
                .subjectType(subjectType)
                .subjectId(subjectId)
                .action(MemoryAction.CREATE)
                .actorType(MemoryActorType.SYSTEM)
                .createdAt(createdAt)
                .build();
    }

    MemoryKnowledgeLink link(UUID entityId, MemoryLinkStatus status) {
        return MemoryKnowledgeLink.builder()
                .entityId(entityId)
                .knowledgeItemId(UUID.randomUUID())
                .status(status)
                .build();
    }
}
