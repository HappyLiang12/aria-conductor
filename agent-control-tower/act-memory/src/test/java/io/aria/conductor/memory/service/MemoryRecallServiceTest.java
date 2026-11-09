package io.aria.conductor.memory.service;

import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.memory.dto.RecallQuery;
import io.aria.conductor.memory.dto.RecallResult;
import io.aria.conductor.memory.model.EntityStatus;
import io.aria.conductor.memory.model.FactStatus;
import io.aria.conductor.memory.model.MemoryEntity;
import io.aria.conductor.memory.model.MemoryFact;
import io.aria.conductor.memory.model.MemoryOrigin;
import io.aria.conductor.memory.model.MemoryRelation;
import io.aria.conductor.memory.repository.MemoryEntityRepository;
import io.aria.conductor.memory.repository.MemoryFactRepository;
import io.aria.conductor.memory.repository.MemoryRelationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Deterministic recall (spec §8.4, plan Lane B Task B-2): lexical mention matching, one-hop
 * expansion, ACTIVE-only current facts, the sensitivity ceiling, ranking, caps, and the
 * same-input-same-output contract. No embeddings are involved anywhere.
 * <p>
 * Repositories are mocked so the tests can assert what the prompt path must never see
 * (PENDING, closed, future, over-ceiling rows) even if a query were to return them.
 */
@ExtendWith(MockitoExtension.class)
class MemoryRecallServiceTest {

    private static final Instant OLD = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant RECENT = Instant.parse("2026-06-01T00:00:00Z");

    @Mock
    MemoryEntityRepository entities;

    @Mock
    MemoryFactRepository facts;

    @Mock
    MemoryRelationRepository relations;

    MemoryRecallService recall;

    @BeforeEach
    void setUp() {
        recall = new MemoryRecallService(entities, facts, relations, new MemoryMentionExtractor());
    }

    @Test
    void recallIsDeterministicAndRanksOperatorFactsFirst() {
        when(entities.findByCanonicalKeyInAndStatus(anyCollection(), eq(EntityStatus.ACTIVE)))
                .thenReturn(List.of(operatorEntity(), repoEntity()));
        when(facts.findByEntityIdInAndStatusAndValidFromLessThanEqualAndValidToIsNull(
                anyCollection(), eq(FactStatus.ACTIVE), any()))
                .thenReturn(List.of(operatorTimezone(), repoBranch()));

        RecallResult first = recall.recall(query("operator timezone Asia/Hong_Kong"));
        RecallResult second = recall.recall(query("operator timezone Asia/Hong_Kong"));

        assertThat(first.facts()).isEqualTo(second.facts());
        assertThat(first.facts().get(0).origin()).isEqualTo("OPERATOR");
        assertThat(first.facts().get(0).entityId()).isEqualTo(operatorEntity().getId().toString());
        assertThat(first.facts().get(0).id()).isEqualTo(operatorTimezone().getId().toString());
    }

    @Test
    void displayNameAndAliasMentionsSelectTheEntity() {
        MemoryEntity person = entity("operator-1", "Jane Doe", "PERSON", MemoryOrigin.OPERATOR,
                "{\"aliases\":[\"JD\"]}");
        MemoryFact editor = fact(person.getId(), "editor", "vim", MemoryOrigin.OPERATOR, RECENT);
        when(entities.findByCanonicalKeyInAndStatus(anyCollection(), eq(EntityStatus.ACTIVE)))
                .thenReturn(List.of());
        when(entities.findAll()).thenReturn(List.of(person));
        when(facts.findByEntityIdInAndStatusAndValidFromLessThanEqualAndValidToIsNull(
                anyCollection(), eq(FactStatus.ACTIVE), any()))
                .thenReturn(List.of(editor));

        assertThat(recall.recall(query("ask jane doe")).entities())
                .extracting(view -> view.id())
                .containsExactly(person.getId().toString());
        assertThat(recall.recall(query("ask jd")).facts())
                .extracting(view -> view.factKey())
                .containsExactly("editor");
    }

    @Test
    void aFocusReferenceRecallsWithoutANameInTheText() {
        MemoryEntity repo = repoEntity();
        when(entities.findByCanonicalKeyInAndStatus(anyCollection(), eq(EntityStatus.ACTIVE)))
                .thenReturn(List.of(repo));
        when(facts.findByEntityIdInAndStatusAndValidFromLessThanEqualAndValidToIsNull(
                anyCollection(), eq(FactStatus.ACTIVE), any()))
                .thenReturn(List.of(repoBranch()));

        RecallResult result = recall.recall(new RecallQuery(
                "", Sensitivity.INTERNAL, "D:/project/aria-conductor"));

        assertThat(result.entities()).extracting(view -> view.canonicalKey())
                .containsExactly("aria-conductor");
    }

    @Test
    void oneHopNeighboursAreIncludedAndTwoHopNeighboursAreNot() {
        MemoryEntity operator = operatorEntity();
        MemoryEntity repo = repoEntity();
        when(entities.findByCanonicalKeyInAndStatus(anyCollection(), eq(EntityStatus.ACTIVE)))
                .thenReturn(List.of(operator));
        when(entities.findAll()).thenReturn(List.of());
        when(relations.findByFromEntityIdAndStatus(operator.getId(), FactStatus.ACTIVE))
                .thenReturn(List.of(relation(operator.getId(), repo.getId())));
        when(relations.findByToEntityIdAndStatus(operator.getId(), FactStatus.ACTIVE))
                .thenReturn(List.of());
        when(entities.findById(repo.getId())).thenReturn(Optional.of(repo));
        when(facts.findByEntityIdInAndStatusAndValidFromLessThanEqualAndValidToIsNull(
                anyCollection(), eq(FactStatus.ACTIVE), any()))
                .thenReturn(List.of(operatorTimezone(), repoBranch()));

        RecallResult result = recall.recall(query("operator"));

        assertThat(result.entities()).extracting(view -> view.id())
                .containsExactly(operator.getId().toString(), repo.getId().toString());
        assertThat(result.facts()).extracting(view -> view.factKey())
                .containsExactlyInAnyOrder("timezone", "branch");
        verify(relations, never()).findByFromEntityIdAndStatus(repo.getId(), FactStatus.ACTIVE);
        verify(relations, never()).findByToEntityIdAndStatus(repo.getId(), FactStatus.ACTIVE);
    }

    @Test
    void onlyCurrentActiveFactsBelowTheCeilingAreRecalled() {
        MemoryEntity operator = operatorEntity();
        MemoryFact current = operatorTimezone();
        MemoryFact pending = fact(operator.getId(), "editor", "vim", MemoryOrigin.EXTRACTION, RECENT);
        pending.setStatus(FactStatus.PENDING);
        MemoryFact future = fact(operator.getId(), "plan", "pro", MemoryOrigin.OPERATOR, RECENT);
        future.setValidFrom(Instant.parse("2030-01-01T00:00:00Z"));
        MemoryFact closed = fact(operator.getId(), "old", "zsh", MemoryOrigin.OPERATOR, RECENT);
        closed.setValidTo(RECENT);
        MemoryFact restricted = fact(operator.getId(), "salary", "1", MemoryOrigin.OPERATOR, RECENT);
        restricted.setSensitivity(Sensitivity.RESTRICTED);
        when(entities.findByCanonicalKeyInAndStatus(anyCollection(), eq(EntityStatus.ACTIVE)))
                .thenReturn(List.of(operator));
        when(entities.findAll()).thenReturn(List.of());
        when(facts.findByEntityIdInAndStatusAndValidFromLessThanEqualAndValidToIsNull(
                anyCollection(), eq(FactStatus.ACTIVE), any()))
                .thenReturn(List.of(current, pending, future, closed, restricted));

        RecallResult result = recall.recall(query("operator"));

        assertThat(result.facts()).extracting(view -> view.factKey()).containsExactly("timezone");
    }

    @Test
    void anEntityAboveTheCeilingNeverContributesFacts() {
        MemoryEntity operator = operatorEntity();
        MemoryEntity secret = entity("secret", "Secret", "PROJECT", MemoryOrigin.OPERATOR, null);
        secret.setSensitivity(Sensitivity.CONFIDENTIAL);
        when(entities.findByCanonicalKeyInAndStatus(anyCollection(), eq(EntityStatus.ACTIVE)))
                .thenReturn(List.of(operator, secret));
        when(entities.findAll()).thenReturn(List.of());
        when(facts.findByEntityIdInAndStatusAndValidFromLessThanEqualAndValidToIsNull(
                anyCollection(), eq(FactStatus.ACTIVE), any()))
                .thenReturn(List.of(operatorTimezone(),
                        fact(secret.getId(), "code", "x", MemoryOrigin.OPERATOR, RECENT)));

        RecallResult result = recall.recall(query("operator secret"));

        assertThat(result.entities()).extracting(view -> view.canonicalKey()).containsExactly("operator");
        assertThat(result.facts()).extracting(view -> view.factKey()).containsExactly("timezone");
    }

    @Test
    void entitiesAreCappedAtTwelve() {
        List<MemoryEntity> many = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            many.add(entity("entity-" + i, "Entity " + i, "PROJECT", MemoryOrigin.OPERATOR, null));
        }
        when(entities.findByCanonicalKeyInAndStatus(anyCollection(), eq(EntityStatus.ACTIVE)))
                .thenReturn(many);
        when(entities.findAll()).thenReturn(List.of());

        RecallResult result = recall.recall(query("entity"));

        assertThat(result.entities()).hasSize(12);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void factsAreCappedAtForty() {
        MemoryEntity operator = operatorEntity();
        List<MemoryFact> manyFacts = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            manyFacts.add(fact(operator.getId(), "key-" + i, "value-" + i, MemoryOrigin.OPERATOR, RECENT));
        }
        when(entities.findByCanonicalKeyInAndStatus(anyCollection(), eq(EntityStatus.ACTIVE)))
                .thenReturn(List.of(operator));
        when(entities.findAll()).thenReturn(List.of());
        when(facts.findByEntityIdInAndStatusAndValidFromLessThanEqualAndValidToIsNull(
                anyCollection(), eq(FactStatus.ACTIVE), any()))
                .thenReturn(manyFacts);

        RecallResult result = recall.recall(query("operator"));

        assertThat(result.facts()).hasSize(40);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void aBlankQueryTouchesNoRepositoryAndRecallsNothing() {
        RecallResult result = recall.recall(new RecallQuery("  ", null, ""));

        assertThat(result.isEmpty()).isTrue();
        verifyNoInteractions(entities, facts, relations);
    }

    // ---------------------------------------------------------------- fixtures

    private static RecallQuery query(String text) {
        return new RecallQuery(text, Sensitivity.INTERNAL, null);
    }

    private static MemoryEntity operatorEntity() {
        return entity("operator", "Operator", "PERSON", MemoryOrigin.OPERATOR, null);
    }

    private static MemoryEntity repoEntity() {
        return entity("aria-conductor", "Aria Conductor", "REPO", MemoryOrigin.RUN, null);
    }

    private static MemoryEntity entity(String canonicalKey, String displayName, String kind,
                                       MemoryOrigin origin, String attributes) {
        return MemoryEntity.builder()
                .id(UUID.nameUUIDFromBytes(canonicalKey.getBytes()))
                .kind(kind)
                .canonicalKey(canonicalKey)
                .displayName(displayName)
                .attributes(attributes)
                .sensitivity(Sensitivity.INTERNAL)
                .status(EntityStatus.ACTIVE)
                .origin(origin)
                .createdAt(OLD)
                .updatedAt(OLD)
                .build();
    }

    private static MemoryFact operatorTimezone() {
        MemoryFact fact = fact(operatorEntity().getId(), "timezone", "Asia/Hong_Kong",
                MemoryOrigin.OPERATOR, RECENT);
        fact.setConfidence(new BigDecimal("1.00"));
        return fact;
    }

    private static MemoryFact repoBranch() {
        return fact(repoEntity().getId(), "branch", "main", MemoryOrigin.RUN, OLD);
    }

    private static MemoryFact fact(UUID entityId, String factKey, String value,
                                   MemoryOrigin origin, Instant updatedAt) {
        return MemoryFact.builder()
                .id(UUID.nameUUIDFromBytes((entityId + "|" + factKey + "|" + value).getBytes()))
                .entityId(entityId)
                .factKey(factKey)
                .valueText(value)
                .valueType("STRING")
                .status(FactStatus.ACTIVE)
                .origin(origin)
                .sensitivity(Sensitivity.INTERNAL)
                .validFrom(OLD)
                .createdAt(OLD)
                .updatedAt(updatedAt)
                .build();
    }

    private static MemoryRelation relation(UUID from, UUID to) {
        return MemoryRelation.builder()
                .id(UUID.nameUUIDFromBytes((from + "->" + to).getBytes()))
                .fromEntityId(from)
                .predicate("OWNS")
                .toEntityId(to)
                .status(FactStatus.ACTIVE)
                .origin(MemoryOrigin.OPERATOR)
                .sensitivity(Sensitivity.INTERNAL)
                .validFrom(OLD)
                .createdAt(OLD)
                .updatedAt(OLD)
                .build();
    }
}
