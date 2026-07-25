package io.aria.conductor.knowledge.repository;

import io.aria.conductor.common.model.KnowledgeVersion;
import io.aria.conductor.common.model.VersionStatus;
import io.aria.conductor.test.DataJpaTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository slice for {@link KnowledgeVersionRepository} declared finders against real
 * H2: newest-first history ordering scoped to one item, and exact item+version lookup.
 */
class KnowledgeVersionRepositorySliceIntegrationTest extends DataJpaTestBase {

    @Autowired
    KnowledgeVersionRepository repository;

    private KnowledgeVersion persistVersion(UUID itemId, String version, Instant createdAt) {
        KnowledgeVersion kv = KnowledgeVersion.builder()
                .id(UUID.randomUUID())
                .knowledgeItemId(itemId)
                .version(version)
                .status(VersionStatus.APPROVED)
                .content("content for " + version)
                .createdAt(createdAt)
                .build();
        entityManager.persist(kv);
        return kv;
    }

    @Test
    void findByKnowledgeItemIdOrderByCreatedAtDesc_returnsNewestFirst_scopedToItem() {
        UUID itemId = UUID.randomUUID();
        Instant base = Instant.parse("2026-03-01T00:00:00Z");
        KnowledgeVersion v1 = persistVersion(itemId, "v0.1.0", base);
        KnowledgeVersion v2 = persistVersion(itemId, "v0.2.0", base.plusSeconds(60));
        KnowledgeVersion v3 = persistVersion(itemId, "v1.0.0", base.plusSeconds(120));
        // Another item's history must not leak in
        persistVersion(UUID.randomUUID(), "v9.9.9", base.plusSeconds(999));
        flushAndClear();

        assertThat(repository.findByKnowledgeItemIdOrderByCreatedAtDesc(itemId))
                .extracting(KnowledgeVersion::getId)
                .containsExactly(v3.getId(), v2.getId(), v1.getId());
    }

    @Test
    void findByKnowledgeItemIdAndVersion_returnsExactVersionOrEmpty() {
        UUID itemId = UUID.randomUUID();
        persistVersion(itemId, "v0.1.0", Instant.now());
        KnowledgeVersion target = persistVersion(itemId, "v0.2.0", Instant.now());
        flushAndClear();

        assertThat(repository.findByKnowledgeItemIdAndVersion(itemId, "v0.2.0"))
                .map(KnowledgeVersion::getId)
                .hasValue(target.getId());
        assertThat(repository.findByKnowledgeItemIdAndVersion(itemId, "v3.0.0")).isEmpty();
        assertThat(repository.findByKnowledgeItemIdAndVersion(UUID.randomUUID(), "v0.2.0")).isEmpty();
    }
}
