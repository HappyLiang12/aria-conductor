package io.aria.conductor.knowledge.repository;

import io.aria.conductor.common.model.KnowledgeItem;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.common.model.KnowledgeVersion;
import io.aria.conductor.common.model.VersionStatus;
import io.aria.conductor.test.DataJpaTestBase;
import io.aria.conductor.test.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository slice for {@link KnowledgeItemRepository} declared finders and the
 * {@code searchByKeyword} JPQL query (#31) against real H2, including its
 * RETIRED/REJECTED exclusion and version-content matching semantics.
 */
class KnowledgeItemRepositorySliceIntegrationTest extends DataJpaTestBase {

    private static final PageRequest PAGE = PageRequest.of(0, 20);

    @Autowired
    KnowledgeItemRepository repository;

    private KnowledgeItem persist(KnowledgeItem item) {
        entityManager.persist(item);
        return item;
    }

    // ==================== derived finders ====================

    @Test
    void findByTypeAndStatus_intersectsBothFilters() {
        KnowledgeItem match = persist(TestDataBuilder.aKnowledgeItem()
                .withType(KnowledgeType.SKILL).withStatus(KnowledgeStatus.APPROVED).build());
        persist(TestDataBuilder.aKnowledgeItem()
                .withType(KnowledgeType.SKILL).withStatus(KnowledgeStatus.PENDING).build());
        persist(TestDataBuilder.aKnowledgeItem()
                .withType(KnowledgeType.PROMPT).withStatus(KnowledgeStatus.APPROVED).build());
        flushAndClear();

        assertThat(repository.findByTypeAndStatus(KnowledgeType.SKILL, KnowledgeStatus.APPROVED))
                .extracting(KnowledgeItem::getId)
                .containsExactly(match.getId());
    }

    @Test
    void findByNameAndType_returnsExactMatchOrEmpty() {
        KnowledgeItem item = persist(TestDataBuilder.aKnowledgeItem()
                .withName("unique-skill-name").withType(KnowledgeType.SKILL).build());
        persist(TestDataBuilder.aKnowledgeItem()
                .withName("unique-skill-name").withType(KnowledgeType.PROMPT).build());
        flushAndClear();

        assertThat(repository.findByNameAndType("unique-skill-name", KnowledgeType.SKILL))
                .map(KnowledgeItem::getId)
                .hasValue(item.getId());
        assertThat(repository.findByNameAndType("missing-name", KnowledgeType.SKILL)).isEmpty();
    }

    @Test
    void findByName_returnsAllMatchesForSharedName() {
        KnowledgeItem first = persist(TestDataBuilder.aKnowledgeItem()
                .withName("shared-name").withType(KnowledgeType.SKILL)
                .withStatus(KnowledgeStatus.PENDING).build());
        KnowledgeItem second = persist(TestDataBuilder.aKnowledgeItem()
                .withName("shared-name").withType(KnowledgeType.PROMPT)
                .withStatus(KnowledgeStatus.APPROVED).build());
        persist(TestDataBuilder.aKnowledgeItem()
                .withName("other-name").withStatus(KnowledgeStatus.APPROVED).build());
        flushAndClear();

        assertThat(repository.findByName("shared-name"))
                .extracting(KnowledgeItem::getId)
                .containsExactlyInAnyOrder(first.getId(), second.getId());
    }

    @Test
    void findByName_noMatchReturnsEmpty() {
        persist(TestDataBuilder.aKnowledgeItem()
                .withName("known-name").withStatus(KnowledgeStatus.PENDING).build());
        flushAndClear();

        assertThat(repository.findByName("missing-name")).isEmpty();
    }

    @Test
    void countByStatus_countsOnlyThatStatus() {
        persist(TestDataBuilder.aKnowledgeItem().withStatus(KnowledgeStatus.PENDING).build());
        persist(TestDataBuilder.aKnowledgeItem().withStatus(KnowledgeStatus.PENDING).build());
        persist(TestDataBuilder.aKnowledgeItem().withStatus(KnowledgeStatus.APPROVED).build());
        flushAndClear();

        assertThat(repository.countByStatus(KnowledgeStatus.PENDING)).isEqualTo(2);
        assertThat(repository.countByStatus(KnowledgeStatus.RETIRED)).isZero();
    }

    @Test
    void findByStatusAndCreatedAtBefore_appliesCutoff() {
        Instant cutoff = Instant.parse("2026-01-01T00:00:00Z");
        KnowledgeItem stale = persist(TestDataBuilder.aKnowledgeItem()
                .withStatus(KnowledgeStatus.PENDING)
                .withCreatedAt(cutoff.minusSeconds(3600)).build());
        persist(TestDataBuilder.aKnowledgeItem()
                .withStatus(KnowledgeStatus.PENDING)
                .withCreatedAt(cutoff.plusSeconds(3600)).build());
        persist(TestDataBuilder.aKnowledgeItem()
                .withStatus(KnowledgeStatus.APPROVED)
                .withCreatedAt(cutoff.minusSeconds(3600)).build());
        flushAndClear();

        assertThat(repository.findByStatusAndCreatedAtBefore(KnowledgeStatus.PENDING, cutoff))
                .extracting(KnowledgeItem::getId)
                .containsExactly(stale.getId());
    }

    @Test
    void findByStatusOrderByUpdatedAtDesc_ordersNewestFirst() {
        Instant base = Instant.parse("2026-06-01T00:00:00Z");
        KnowledgeItem older = persist(TestDataBuilder.aKnowledgeItem()
                .withStatus(KnowledgeStatus.APPROVED).withUpdatedAt(base.minusSeconds(600)).build());
        KnowledgeItem newer = persist(TestDataBuilder.aKnowledgeItem()
                .withStatus(KnowledgeStatus.APPROVED).withUpdatedAt(base).build());
        flushAndClear();

        assertThat(repository.findByStatusOrderByUpdatedAtDesc(KnowledgeStatus.APPROVED))
                .extracting(KnowledgeItem::getId)
                .containsExactly(newer.getId(), older.getId());
    }

    // ==================== searchByKeyword (@Query) ====================

    @Test
    void searchByKeyword_matchesNameAndDescription_caseInsensitively() {
        KnowledgeItem byName = persist(TestDataBuilder.aKnowledgeItem()
                .withName("Alpha PARSER Skill").withDescription("nothing here")
                .withStatus(KnowledgeStatus.PENDING).build());
        KnowledgeItem byDescription = persist(TestDataBuilder.aKnowledgeItem()
                .withName("beta-skill").withDescription("A robust parser for logs")
                .withStatus(KnowledgeStatus.APPROVED).build());
        persist(TestDataBuilder.aKnowledgeItem()
                .withName("gamma-skill").withDescription("unrelated")
                .withStatus(KnowledgeStatus.APPROVED).build());
        flushAndClear();

        // Callers pass a pre-lowercased LIKE pattern (see KnowledgeItemRepository javadoc)
        assertThat(repository.searchByKeyword("%parser%", null, null, PAGE))
                .extracting(KnowledgeItem::getId)
                .containsExactlyInAnyOrder(byName.getId(), byDescription.getId());
    }

    @Test
    void searchByKeyword_excludesRetiredAndRejected_evenOnNameMatch() {
        persist(TestDataBuilder.aKnowledgeItem()
                .withName("needle-retired").withStatus(KnowledgeStatus.RETIRED).build());
        persist(TestDataBuilder.aKnowledgeItem()
                .withName("needle-rejected").withStatus(KnowledgeStatus.REJECTED).build());
        KnowledgeItem draft = persist(TestDataBuilder.aKnowledgeItem()
                .withName("needle-draft").withStatus(KnowledgeStatus.DRAFT).build());
        flushAndClear();

        assertThat(repository.searchByKeyword("%needle%", null, null, PAGE))
                .extracting(KnowledgeItem::getId)
                .containsExactly(draft.getId());
    }

    @Test
    void searchByKeyword_findsItemsThroughVersionContent() {
        KnowledgeItem item = persist(TestDataBuilder.aKnowledgeItem()
                .withName("plain-name").withDescription("plain description")
                .withStatus(KnowledgeStatus.APPROVED).build());
        entityManager.persist(KnowledgeVersion.builder()
                .id(UUID.randomUUID())
                .knowledgeItemId(item.getId())
                .version("1.0.0")
                .status(VersionStatus.APPROVED)
                .content("uses the SPECIAL-token internally")
                .createdAt(Instant.now())
                .build());
        flushAndClear();

        assertThat(repository.searchByKeyword("%special-token%", null, null, PAGE))
                .extracting(KnowledgeItem::getId)
                .containsExactly(item.getId());
    }

    @Test
    void searchByKeyword_nullKeyword_appliesTypeAndStatusFiltersOnly() {
        KnowledgeItem approvedSkill = persist(TestDataBuilder.aKnowledgeItem()
                .withType(KnowledgeType.SKILL).withStatus(KnowledgeStatus.APPROVED).build());
        persist(TestDataBuilder.aKnowledgeItem()
                .withType(KnowledgeType.SKILL).withStatus(KnowledgeStatus.PENDING).build());
        persist(TestDataBuilder.aKnowledgeItem()
                .withType(KnowledgeType.PROMPT).withStatus(KnowledgeStatus.APPROVED).build());
        flushAndClear();

        assertThat(repository.searchByKeyword(null, KnowledgeType.SKILL, KnowledgeStatus.APPROVED, PAGE))
                .extracting(KnowledgeItem::getId)
                .containsExactly(approvedSkill.getId());
    }
}
