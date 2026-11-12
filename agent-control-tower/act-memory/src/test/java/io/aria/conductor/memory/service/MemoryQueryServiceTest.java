package io.aria.conductor.memory.service;

import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.memory.dto.MemoryEntityView;
import io.aria.conductor.memory.dto.MemoryFactView;
import io.aria.conductor.memory.model.EntityStatus;
import io.aria.conductor.memory.model.FactStatus;
import io.aria.conductor.memory.model.MemoryEntity;
import io.aria.conductor.memory.model.MemoryFact;
import io.aria.conductor.memory.model.MemoryOrigin;
import io.aria.conductor.memory.repository.MemoryEntityRepository;
import io.aria.conductor.memory.repository.MemoryFactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The read model behind the REST/MCP memory surfaces: entity list and detail, current facts
 * (ACTIVE only — a PENDING proposal is review material, never memory), exact fact-key
 * lookup, and the full validity history. Every read is deterministic.
 */
@ExtendWith(MockitoExtension.class)
class MemoryQueryServiceTest {

    private static final Instant OLD = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    MemoryEntityRepository entities;

    @Mock
    MemoryFactRepository facts;

    MemoryQueryService query;

    @BeforeEach
    void setUp() {
        query = new MemoryQueryService(entities, facts);
    }

    @Test
    void listEntitiesQueriesByKindAndStatusAndAppliesTheLimit() {
        MemoryEntity b = entity("b-repo", EntityStatus.ACTIVE);
        MemoryEntity a = entity("a-repo", EntityStatus.ACTIVE);
        when(entities.findByKindAndStatus("REPO", EntityStatus.ACTIVE)).thenReturn(List.of(b, a));

        List<MemoryEntityView> page = query.listEntities("REPO", null, 1);

        assertThat(page).extracting(MemoryEntityView::canonicalKey).containsExactly("a-repo");
    }

    /**
     * T53 F-B2 (live bar): `list_memory_entities {"kind":"person"}` answered `{"ok":true,"data":[]}`
     * while `person:happy` was ACTIVE, because the filter was passed unchanged into an exact
     * match against the stored `PERSON`. The prompt teaches `person / person:happy` and the tool
     * description taught `PERSON`, so both spellings must name the same rows; the canonical form
     * is the stored uppercase vocabulary.
     */
    @Test
    void listEntitiesNormalisesTheKindFilterSoTheTaughtLowercaseSpellingFindsTheStoredEntity() {
        MemoryEntity person = MemoryEntity.builder()
                .id(UUID.nameUUIDFromBytes("person:happy".getBytes()))
                .kind("PERSON")
                .canonicalKey("person:happy")
                .displayName("Happy")
                .sensitivity(Sensitivity.INTERNAL)
                .status(EntityStatus.ACTIVE)
                .origin(MemoryOrigin.OPERATOR)
                .createdAt(OLD)
                .updatedAt(OLD)
                .build();
        when(entities.findByKindAndStatus("PERSON", EntityStatus.ACTIVE)).thenReturn(List.of(person));

        List<MemoryEntityView> page = query.listEntities("person", null, 0);

        assertThat(page).extracting(MemoryEntityView::canonicalKey).containsExactly("person:happy");
        verify(entities).findByKindAndStatus("PERSON", EntityStatus.ACTIVE);
    }

    /**
     * The positive control for the test above, and the other half of F-B2: a known kind with no
     * matching rows still answers an empty page, while an unknown kind is refused with a named
     * VALIDATION error. An empty list therefore never means "unknown kind" and an unknown kind
     * can never be mistaken for "nothing exists".
     */
    @Test
    void listEntitiesKeepsAnEmptyKnownKindAndRefusesAnUnknownKind() {
        when(entities.findByKindAndStatus("PERSON", EntityStatus.ACTIVE)).thenReturn(List.of());

        assertThat(query.listEntities("PERSON", EntityStatus.ACTIVE, 0))
                .as("a known kind with no rows is an empty page, not an error")
                .isEmpty();

        assertThatThrownBy(() -> query.listEntities("personn", EntityStatus.ACTIVE, 0))
                .as("a typo must not be reported as an empty kind")
                .isInstanceOf(MemoryValidationException.class)
                .hasMessageContaining("personn")
                .hasMessageContaining("PERSON")
                .hasMessageContaining("case-insensitive");
    }

    @Test
    void listEntitiesWithoutAKindKeepsOnlyTheRequestedStatus() {
        MemoryEntity active = entity("active-one", EntityStatus.ACTIVE);
        MemoryEntity retired = entity("retired-one", EntityStatus.RETIRED);
        when(entities.findAll()).thenReturn(List.of(retired, active));

        List<MemoryEntityView> page = query.listEntities(null, EntityStatus.ACTIVE, 0);

        assertThat(page).extracting(MemoryEntityView::canonicalKey).containsExactly("active-one");
    }

    @Test
    void currentFactsAreTheActiveOpenRows() {
        UUID entityId = UUID.randomUUID();
        MemoryFact newest = fact(entityId, "timezone", FactStatus.ACTIVE);
        MemoryFact older = fact(entityId, "editor", FactStatus.ACTIVE);
        when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(entityId, FactStatus.ACTIVE))
                .thenReturn(List.of(newest, older));

        List<MemoryFactView> current = query.currentFacts(entityId);

        assertThat(current).extracting(MemoryFactView::factKey).containsExactly("timezone", "editor");
        assertThat(current).allSatisfy(view -> assertThat(view.status()).isEqualTo("ACTIVE"));
        verify(facts).findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(entityId, FactStatus.ACTIVE);
    }

    @Test
    void currentFactMatchesByFactKey() {
        UUID entityId = UUID.randomUUID();
        when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(entityId, FactStatus.ACTIVE))
                .thenReturn(List.of(fact(entityId, "timezone", FactStatus.ACTIVE),
                        fact(entityId, "editor", FactStatus.ACTIVE)));

        assertThat(query.currentFact(entityId, "editor"))
                .map(MemoryFactView::valueText)
                .contains("value");
        assertThat(query.currentFact(entityId, "missing")).isEmpty();
    }

    @Test
    void factHistoryReturnsClosedRowsToo() {
        UUID entityId = UUID.randomUUID();
        MemoryFact retired = fact(entityId, "timezone", FactStatus.RETIRED);
        retired.setValidTo(OLD.plusSeconds(60));
        when(facts.findByEntityIdOrderByValidFromDesc(entityId))
                .thenReturn(List.of(fact(entityId, "timezone", FactStatus.ACTIVE), retired));

        List<MemoryFactView> history = query.factHistory(entityId);

        assertThat(history).extracting(MemoryFactView::status).containsExactly("ACTIVE", "RETIRED");
    }

    @Test
    void getEntityReturnsTheViewOrANamedNotFound() {
        MemoryEntity entity = entity("operator", EntityStatus.ACTIVE);
        when(entities.findById(entity.getId())).thenReturn(Optional.of(entity));

        assertThat(query.getEntity(entity.getId()).canonicalKey()).isEqualTo("operator");
        assertThatThrownBy(() -> query.getEntity(UUID.randomUUID()))
                .isInstanceOf(MemoryNotFoundException.class)
                .hasMessageContaining("not found");
    }

    /**
     * The read-path guarantee has two directions: the default/detail read must not expose an
     * unapproved entity, and the operator's explicit PENDING filter must, or the proposal could
     * never be approved. Recall and the context contributor query ACTIVE only by construction.
     */
    @Test
    void aPendingEntityIsRefusedByTheDetailReadAndVisibleOnlyOnAnExplicitFilter() {
        MemoryEntity proposed = entity("proposed-person", EntityStatus.PENDING);
        MemoryEntity active = entity("operator", EntityStatus.ACTIVE);
        when(entities.findById(proposed.getId())).thenReturn(Optional.of(proposed));
        when(entities.findAll()).thenReturn(List.of(proposed, active));

        assertThatThrownBy(() -> query.getEntity(proposed.getId()))
                .as("a worker-facing detail read never returns a proposal")
                .isInstanceOf(MemoryNotFoundException.class)
                .hasMessageContaining("not found");
        assertThat(query.listEntities(null, null, 50))
                .as("the default list is ACTIVE-only")
                .extracting(MemoryEntityView::canonicalKey)
                .containsExactly("operator");
        assertThat(query.listEntities(null, EntityStatus.PENDING, 50))
                .as("the operator must be able to see what to approve")
                .extracting(MemoryEntityView::canonicalKey)
                .containsExactly("proposed-person");
    }

    /**
     * Spec :309-313 — the operator cannot approve what they cannot see. The explicit PENDING
     * detail read is a separate flag, so the default read pinned above stays fail-closed for
     * every agent-facing caller.
     */
    @Test
    void theExplicitPendingDetailReadReturnsThePendingEntity() {
        MemoryEntity proposed = entity("proposed-person", EntityStatus.PENDING);
        when(entities.findById(proposed.getId())).thenReturn(Optional.of(proposed));

        MemoryEntityView view = query.getEntity(proposed.getId(), true);

        assertThat(view.status()).isEqualTo("PENDING");
        assertThat(view.canonicalKey()).isEqualTo("proposed-person");
    }

    // ---------------------------------------------------------------- fixtures

    private static MemoryEntity entity(String canonicalKey, EntityStatus status) {
        return MemoryEntity.builder()
                .id(UUID.nameUUIDFromBytes(canonicalKey.getBytes()))
                .kind("REPO")
                .canonicalKey(canonicalKey)
                .displayName(canonicalKey)
                .sensitivity(Sensitivity.INTERNAL)
                .status(status)
                .origin(MemoryOrigin.OPERATOR)
                .createdAt(OLD)
                .updatedAt(OLD)
                .build();
    }

    private static MemoryFact fact(UUID entityId, String factKey, FactStatus status) {
        return MemoryFact.builder()
                .id(UUID.randomUUID())
                .entityId(entityId)
                .factKey(factKey)
                .valueText("value")
                .valueType("STRING")
                .status(status)
                .origin(MemoryOrigin.OPERATOR)
                .sensitivity(Sensitivity.INTERNAL)
                .validFrom(OLD)
                .createdAt(OLD)
                .updatedAt(OLD)
                .build();
    }
}
