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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

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
import static org.mockito.Mockito.times;
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
        stubUniverse(person);
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

    /**
     * The prompt hot path must not read the whole entity table, and must not load each
     * neighbour with its own {@code findById}: the lexical universe is one bounded
     * status-scoped page and the one-hop expansion is one {@code IN} query.
     */
    @Test
    void recallOverMoreThanTwoHundredEntitiesStaysBoundedAndNeverScansTheTable() {
        List<MemoryEntity> fixture = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            fixture.add(entity("entity-" + i, "Entity " + i, "PROJECT", MemoryOrigin.RUN, null));
        }
        MemoryEntity operator = operatorEntity();
        when(entities.findByCanonicalKeyInAndStatus(anyCollection(), eq(EntityStatus.ACTIVE)))
                .thenReturn(List.of(operator));
        when(entities.findByStatusOrderByUpdatedAtDesc(eq(EntityStatus.ACTIVE), any(Pageable.class)))
                .thenReturn(fixture.subList(0, MemoryRecallService.LEXICAL_UNIVERSE_LIMIT));
        when(facts.findByEntityIdInAndStatusAndValidFromLessThanEqualAndValidToIsNull(
                anyCollection(), eq(FactStatus.ACTIVE), any()))
                .thenReturn(List.of(operatorTimezone()));

        RecallResult first = recall.recall(query("operator timezone"));
        RecallResult second = recall.recall(query("operator timezone"));

        assertThat(first.entities()).extracting(view -> view.canonicalKey()).containsExactly("operator");
        assertThat(first.facts()).extracting(view -> view.factKey()).containsExactly("timezone");
        assertThat(second.facts()).as("same input, same output").isEqualTo(first.facts());
        verify(entities, never()).findAll();

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(entities, times(2)).findByStatusOrderByUpdatedAtDesc(eq(EntityStatus.ACTIVE), page.capture());
        assertThat(page.getAllValues()).allSatisfy(captured -> {
            assertThat(captured.getPageSize())
                    .as("the lexical universe is one bounded page")
                    .isEqualTo(MemoryRecallService.LEXICAL_UNIVERSE_LIMIT);
            assertThat(captured.getOffset()).isZero();
        });
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
        stubUniverse();
        when(relations.findByFromEntityIdAndStatus(operator.getId(), FactStatus.ACTIVE))
                .thenReturn(List.of(relation(operator.getId(), repo.getId())));
        when(relations.findByToEntityIdAndStatus(operator.getId(), FactStatus.ACTIVE))
                .thenReturn(List.of());
        when(entities.findByIdInAndStatus(anyCollection(), eq(EntityStatus.ACTIVE)))
                .thenReturn(List.of(repo));
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
        verify(entities, never()).findById(any());
        verify(entities, times(1)).findByIdInAndStatus(anyCollection(), eq(EntityStatus.ACTIVE));
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
        stubUniverse();
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
        stubUniverse();
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
        stubUniverse();

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
        stubUniverse();
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

    // ---------------------------------------------------------------- ranking contract

    @Test
    void sourceAuthorityOutranksRecency() {
        MemoryEntity operator = operatorEntity();
        MemoryFact operatorOld = fact(operator.getId(), "timezone", "Asia/Hong_Kong", MemoryOrigin.OPERATOR, OLD);
        MemoryFact runRecent = fact(operator.getId(), "branch", "main", MemoryOrigin.RUN, RECENT);
        when(entities.findByCanonicalKeyInAndStatus(anyCollection(), eq(EntityStatus.ACTIVE)))
                .thenReturn(List.of(operator));
        stubUniverse();
        when(facts.findByEntityIdInAndStatusAndValidFromLessThanEqualAndValidToIsNull(
                anyCollection(), eq(FactStatus.ACTIVE), any()))
                .thenReturn(List.of(runRecent, operatorOld));

        assertThat(recall.recall(query("operator")).facts())
                .extracting(view -> view.factKey())
                .as("OPERATOR+old beats RUN+recent: recency never outranks authority")
                .containsExactly("timezone", "branch");
    }

    @Test
    void confidenceThenIdBreakTheRemainingTies() {
        MemoryEntity operator = operatorEntity();
        MemoryFact lower = withConfidence(fact(operator.getId(), "a-low", "1", MemoryOrigin.OPERATOR, RECENT), "0.40");
        MemoryFact higher = withConfidence(fact(operator.getId(), "b-high", "2", MemoryOrigin.OPERATOR, RECENT), "0.90");
        MemoryFact tied = withConfidence(fact(operator.getId(), "c-tied", "3", MemoryOrigin.OPERATOR, RECENT), "0.40");
        when(entities.findByCanonicalKeyInAndStatus(anyCollection(), eq(EntityStatus.ACTIVE)))
                .thenReturn(List.of(operator));
        stubUniverse();
        when(facts.findByEntityIdInAndStatusAndValidFromLessThanEqualAndValidToIsNull(
                anyCollection(), eq(FactStatus.ACTIVE), any()))
                .thenReturn(List.of(lower, higher, tied));

        List<String> order = recall.recall(query("operator")).facts().stream()
                .map(view -> view.factKey())
                .toList();

        String lowerId = lower.getId().toString();
        String tiedId = tied.getId().toString();
        String lowTieFirst = lowerId.compareTo(tiedId) <= 0 ? "a-low" : "c-tied";
        String lowTieSecond = "a-low".equals(lowTieFirst) ? "c-tied" : "a-low";
        assertThat(order)
                .as("confidence decides first, and the last tie falls back to the id")
                .containsExactly("b-high", lowTieFirst, lowTieSecond);
    }

    @Test
    void aCanonicalKeyMentionOutranksADisplayNameMention() {
        MemoryEntity named = entity("run-only", "Operator", "PERSON", MemoryOrigin.RUN, null);
        MemoryEntity exact = entity("operator", "Old Name", "PERSON", MemoryOrigin.EXTRACTION, null);
        when(entities.findByCanonicalKeyInAndStatus(anyCollection(), eq(EntityStatus.ACTIVE)))
                .thenReturn(List.of(exact));
        stubUniverse(named, exact);

        assertThat(recall.recall(query("operator")).entities())
                .extracting(view -> view.canonicalKey())
                .as("mention source is the first key: an exact canonical key beats a display name")
                .containsExactly("operator", "run-only");
    }

    @Test
    void aPublicFactIsRecalledAtTheInternalCeiling() {
        MemoryEntity operator = operatorEntity();
        MemoryFact publicFact = fact(operator.getId(), "locale", "en", MemoryOrigin.RUN, RECENT);
        publicFact.setSensitivity(Sensitivity.PUBLIC);
        when(entities.findByCanonicalKeyInAndStatus(anyCollection(), eq(EntityStatus.ACTIVE)))
                .thenReturn(List.of(operator));
        stubUniverse();
        when(facts.findByEntityIdInAndStatusAndValidFromLessThanEqualAndValidToIsNull(
                anyCollection(), eq(FactStatus.ACTIVE), any()))
                .thenReturn(List.of(publicFact));

        assertThat(recall.recall(query("operator")).facts())
                .as("the ceiling compares Sensitivity ordinals, not names: PUBLIC < INTERNAL")
                .extracting(view -> view.factKey())
                .containsExactly("locale");
    }

    @Test
    void aNullSensitivityFailsClosedAsRestricted() {
        MemoryEntity operator = operatorEntity();
        MemoryFact unlabelled = fact(operator.getId(), "salary", "1", MemoryOrigin.OPERATOR, RECENT);
        unlabelled.setSensitivity(null);
        when(entities.findByCanonicalKeyInAndStatus(anyCollection(), eq(EntityStatus.ACTIVE)))
                .thenReturn(List.of(operator));
        stubUniverse();
        when(facts.findByEntityIdInAndStatusAndValidFromLessThanEqualAndValidToIsNull(
                anyCollection(), eq(FactStatus.ACTIVE), any()))
                .thenReturn(List.of(unlabelled));

        assertThat(recall.recall(query("operator")).facts())
                .as("a fact with no sensitivity is never assumed to be safe")
                .isEmpty();
        assertThat(recall.recall(new RecallQuery("operator", Sensitivity.RESTRICTED, "")).facts())
                .as("only a provider at the top of the scale may see it")
                .hasSize(1);
    }

    @Test
    void anEntityWithNullSensitivityIsNotRecalledBelowTheTopCeiling() {
        MemoryEntity unlabelled = entity("unlabelled", "Unlabelled", "PROJECT", MemoryOrigin.OPERATOR, null);
        unlabelled.setSensitivity(null);
        when(entities.findByCanonicalKeyInAndStatus(anyCollection(), eq(EntityStatus.ACTIVE)))
                .thenReturn(List.of(unlabelled));

        assertThat(recall.recall(query("unlabelled")).entities()).isEmpty();
    }

    /**
     * Minor 6 (T10 fix round 1). {@code addRelation} does not guard the target's lifecycle, so an
     * OPERATOR can write an ACTIVE relation into a PENDING entity (the relation row below is
     * exactly what that call writes: ACTIVE, OPERATOR origin, PENDING target — see the decision
     * comment on {@code MemoryCommandService.addRelation}). A relation is geometry, not read
     * authority: recall expands only from ACTIVE matches and re-filters every neighbour to
     * ACTIVE, so the unapproved entity never reaches the prompt — even if the neighbour query
     * leaked the row.
     */
    @Test
    void anActiveRelationIntoAPendingEntityNeverSurfacesItInRecall() {
        MemoryEntity operator = operatorEntity();
        MemoryEntity pending = entity("person:happy", "Happy", "PERSON", MemoryOrigin.EXTRACTION, null);
        pending.setStatus(EntityStatus.PENDING);
        when(entities.findByCanonicalKeyInAndStatus(anyCollection(), eq(EntityStatus.ACTIVE)))
                .thenReturn(List.of(operator));
        stubUniverse(operator);
        when(relations.findByFromEntityIdAndStatus(operator.getId(), FactStatus.ACTIVE))
                .thenReturn(List.of(relation(operator.getId(), pending.getId())));
        when(relations.findByToEntityIdAndStatus(operator.getId(), FactStatus.ACTIVE))
                .thenReturn(List.of());
        when(entities.findByIdInAndStatus(anyCollection(), eq(EntityStatus.ACTIVE)))
                .thenReturn(List.of(pending));

        RecallResult result = recall.recall(query("operator"));

        assertThat(result.entities()).extracting(view -> view.canonicalKey())
                .as("a PENDING entity is never memory, not even through a relation")
                .containsExactly("operator");
    }

    /**
     * T58 / F-O3. On the live bar the entity was stored with displayName = its canonical key
     * (`MemoryCommandService:145`), so the text "…me, Happy?" named the operator and recall still
     * returned nothing — the name lived only in an ACTIVE `display_name` fact, and fact values are
     * deliberately never matched. The canonical key's tail is the handle; the fact proves the value
     * is present without recall reading it.
     */
    @Test
    void anEntityWhoseDisplayNameWasDefaultedToItsKeyIsRecalledByItsOwnTailName() {
        MemoryEntity person = entity("person:happy", "person:happy", "PERSON", MemoryOrigin.EXTRACTION, null);
        MemoryFact name = fact(person.getId(), "display_name", "Happy", MemoryOrigin.OPERATOR, RECENT);
        when(entities.findByCanonicalKeyInAndStatus(anyCollection(), eq(EntityStatus.ACTIVE)))
                .thenReturn(List.of());
        stubUniverse(person);
        when(facts.findByEntityIdInAndStatusAndValidFromLessThanEqualAndValidToIsNull(
                anyCollection(), eq(FactStatus.ACTIVE), any()))
                .thenReturn(List.of(name));

        RecallResult result = recall.recall(query("What do you remember about me, Happy?"));

        assertThat(result.entities()).extracting(view -> view.canonicalKey())
                .containsExactly("person:happy");
        assertThat(result.facts()).extracting(view -> view.factKey())
                .containsExactly("display_name");
    }

    /**
     * T58 / F-B4. Both ACTIVE REPO entities from the live bar, keyed by path and by URL, are named
     * by the text through their trailing segment. Neither canonical key is contained in the text, so
     * neither the exact-key pass nor the whole-key lexical pass can find them.
     */
    @Test
    void theRepositoryEntitiesAreRecalledByTheRepositoryNameInTheText() {
        MemoryEntity pathKey = entity("HappyLiang12/aria-conductor", "HappyLiang12/aria-conductor",
                "REPO", MemoryOrigin.RUN, null);
        MemoryEntity urlKey = entity("https://github.com/HappyLiang12/aria-conductor.git",
                "https://github.com/HappyLiang12/aria-conductor.git", "REPO", MemoryOrigin.RUN, null);
        when(entities.findByCanonicalKeyInAndStatus(anyCollection(), eq(EntityStatus.ACTIVE)))
                .thenReturn(List.of());
        stubUniverse(pathKey, urlKey);
        when(facts.findByEntityIdInAndStatusAndValidFromLessThanEqualAndValidToIsNull(
                anyCollection(), eq(FactStatus.ACTIVE), any()))
                .thenReturn(List.of());

        RecallResult result = recall.recall(query("aria-conductor repository release notes"));

        assertThat(result.entities()).extracting(view -> view.canonicalKey())
                .containsExactlyInAnyOrder("HappyLiang12/aria-conductor",
                        "https://github.com/HappyLiang12/aria-conductor.git");
        assertThat(result.entities()).extracting(view -> view.displayName())
                .as("the stored display name is still the defaulted key — recall matched the key's tail")
                .containsExactlyInAnyOrder("HappyLiang12/aria-conductor",
                        "https://github.com/HappyLiang12/aria-conductor.git");
    }

    /**
     * T61 / re-review m1 + o1. A namespace-only canonical key (`person:`) has no tail, so ordinary
     * prose no longer names it. Before the shape guard the tail "person" ranked the unrelated entity
     * as a CANONICAL_KEY match (rank 0), ahead of the real DISPLAY_NAME match, and its facts entered
     * the prompt with it — the displacement the re-review observed inside MAX_ENTITIES.
     */
    @Test
    void aNamespaceOnlyKeyIsNotRecalledFromProseAndCannotDisplaceADisplayNameMatch() {
        MemoryEntity namespaceOnly = entity("person:", "person:", "PERSON", MemoryOrigin.EXTRACTION, null);
        MemoryEntity named = entity("operator-1", "Person", "PERSON", MemoryOrigin.OPERATOR, null);
        MemoryFact strayFact = fact(namespaceOnly.getId(), "note", "unrelated", MemoryOrigin.EXTRACTION, RECENT);
        MemoryFact namedFact = fact(named.getId(), "timezone", "Asia/Hong_Kong", MemoryOrigin.OPERATOR, RECENT);
        when(entities.findByCanonicalKeyInAndStatus(anyCollection(), eq(EntityStatus.ACTIVE)))
                .thenReturn(List.of());
        stubUniverse(namespaceOnly, named);
        when(facts.findByEntityIdInAndStatusAndValidFromLessThanEqualAndValidToIsNull(
                anyCollection(), eq(FactStatus.ACTIVE), any()))
                .thenReturn(List.of(strayFact, namedFact));

        RecallResult result = recall.recall(query("who is the person here"));

        assertThat(result.entities()).extracting(view -> view.canonicalKey())
                .as("only the display-name match is a mention; the namespace-only key is not")
                .containsExactly("operator-1");
        assertThat(result.facts()).extracting(view -> view.factKey())
                .as("and the unrelated entity's facts never reach the prompt")
                .containsExactly("timezone");
    }

    // ---------------------------------------------------------------- fixtures

    private void stubUniverse(MemoryEntity... rows) {
        when(entities.findByStatusOrderByUpdatedAtDesc(eq(EntityStatus.ACTIVE), any(Pageable.class)))
                .thenReturn(List.of(rows));
    }

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

    private static MemoryFact withConfidence(MemoryFact fact, String confidence) {
        fact.setConfidence(new BigDecimal(confidence));
        return fact;
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
