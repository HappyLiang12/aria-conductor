package io.aria.conductor.dashboard.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.memory.dto.MemoryActor;
import io.aria.conductor.memory.model.EntityStatus;
import io.aria.conductor.memory.model.FactStatus;
import io.aria.conductor.memory.model.MemoryAction;
import io.aria.conductor.memory.model.MemoryActorType;
import io.aria.conductor.memory.model.MemoryAssertion;
import io.aria.conductor.memory.model.MemoryEntity;
import io.aria.conductor.memory.model.MemoryFact;
import io.aria.conductor.memory.model.MemoryOrigin;
import io.aria.conductor.memory.model.MemorySubjectType;
import io.aria.conductor.memory.repository.MemoryAssertionRepository;
import io.aria.conductor.memory.repository.MemoryEntityRepository;
import io.aria.conductor.memory.repository.MemoryFactRepository;
import io.aria.conductor.memory.service.MemoryConflictException;
import io.aria.conductor.memory.service.MemoryNotFoundException;
import io.aria.conductor.memory.service.MemoryValueGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the operator lifecycle operations that the merged command service does not expose:
 * rename, alias merge, entity retire, and fact retire. Each write must leave one assertion
 * and must refuse a non-operator before touching a row.
 */
class MemoryLifecycleServiceTest {

    private static final MemoryActor OPERATOR = new MemoryActor(MemoryActorType.OPERATOR, "operator-bearer");
    private static final MemoryActor WORKER = new MemoryActor(MemoryActorType.RUN, "run-1");
    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");

    private MemoryEntityRepository entities;
    private MemoryFactRepository facts;
    private MemoryAssertionRepository assertions;
    private MemoryLifecycleService service;

    @BeforeEach
    void setUp() {
        entities = mock(MemoryEntityRepository.class);
        facts = mock(MemoryFactRepository.class);
        assertions = mock(MemoryAssertionRepository.class);
        service = new MemoryLifecycleService(entities, facts, assertions,
                new MemoryValueGuard(), new ObjectMapper());
    }

    private static MemoryEntity entity(UUID id, String attributes) {
        return MemoryEntity.builder()
                .id(id)
                .kind("PERSON")
                .canonicalKey("operator")
                .displayName("Operator")
                .attributes(attributes)
                .sensitivity(Sensitivity.INTERNAL)
                .status(EntityStatus.ACTIVE)
                .origin(MemoryOrigin.OPERATOR)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }

    private static MemoryFact fact(UUID entityId, FactStatus status) {
        return MemoryFact.builder()
                .id(UUID.randomUUID())
                .entityId(entityId)
                .factKey("timezone")
                .valueText("UTC")
                .valueType("STRING")
                .status(status)
                .origin(MemoryOrigin.OPERATOR)
                .sensitivity(Sensitivity.INTERNAL)
                .validFrom(NOW)
                .build();
    }

    @Test
    void renameReplacesDisplayNameAndWritesOneAssertion() {
        UUID id = UUID.randomUUID();
        MemoryEntity entity = entity(id, null);
        when(entities.findById(id)).thenReturn(Optional.of(entity));

        MemoryEntity renamed = service.renameEntity(id, "Operator Prime", OPERATOR);

        assertThat(renamed.getDisplayName()).isEqualTo("Operator Prime");
        ArgumentCaptor<MemoryAssertion> assertion = ArgumentCaptor.forClass(MemoryAssertion.class);
        verify(assertions).save(assertion.capture());
        assertThat(assertion.getValue().getAction()).isEqualTo(MemoryAction.UPDATE);
        assertThat(assertion.getValue().getAfterJson()).contains("Operator Prime");
    }

    @Test
    void addAliasesPreservesOtherAttributes() {
        UUID id = UUID.randomUUID();
        MemoryEntity entity = entity(id, "{\"timezone\":\"UTC\"}");
        when(entities.findById(id)).thenReturn(Optional.of(entity));

        MemoryEntity changed = service.addAliases(id, List.of("op", "the operator"), OPERATOR);

        assertThat(changed.getAttributes()).contains("\"timezone\":\"UTC\"", "op", "the operator");
    }

    @Test
    void retireEntityMovesTheLifecycleOutOfActive() {
        UUID id = UUID.randomUUID();
        when(entities.findById(id)).thenReturn(Optional.of(entity(id, null)));

        MemoryEntity retired = service.retireEntity(id, OPERATOR);

        assertThat(retired.getStatus()).isEqualTo(EntityStatus.RETIRED);
        verify(assertions).save(any(MemoryAssertion.class));
    }

    /**
     * Retiring an entity must sweep its open graph, exactly as a re-proposal does
     * ({@code MemoryCommandService.retireLiveGraph}). Without the sweep a PENDING fact stayed
     * PENDING under a RETIRED entity, and the operator's next decision on it was refused with
     * "which is RETIRED; the entity is no longer active …" — a message that matches neither client
     * marker, so the card kept a Retry that could only ever 409.
     */
    @Test
    void retireEntityRetiresItsOpenFactsAndLeavesNoDecideLessProposal() {
        UUID id = UUID.randomUUID();
        when(entities.findById(id)).thenReturn(Optional.of(entity(id, null)));
        MemoryFact first = fact(id, FactStatus.PENDING);
        MemoryFact second = fact(id, FactStatus.PENDING);
        MemoryFact active = fact(id, FactStatus.ACTIVE);
        when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(id, FactStatus.PENDING))
                .thenReturn(List.of(first, second));
        when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(id, FactStatus.ACTIVE))
                .thenReturn(List.of(active));

        service.retireEntity(id, OPERATOR);

        for (MemoryFact swept : List.of(first, second, active)) {
            assertThat(swept.getStatus())
                    .as("an open fact of a retired entity is retired with it, like retireLiveGraph")
                    .isEqualTo(FactStatus.RETIRED);
            assertThat(swept.getValidTo()).as("and its validity window is closed").isNotNull();
            assertThat(swept.getProposalHash())
                    .as("and it releases the proposal hash, so the value can be proposed again")
                    .isNull();
            verify(facts).save(swept);
        }
        ArgumentCaptor<MemoryAssertion> written = ArgumentCaptor.forClass(MemoryAssertion.class);
        verify(assertions, org.mockito.Mockito.times(4)).save(written.capture());
        assertThat(written.getAllValues())
                .as("one assertion per closed row plus the entity's own RETIRE")
                .extracting(MemoryAssertion::getSubjectType, MemoryAssertion::getAction)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(MemorySubjectType.ENTITY, MemoryAction.RETIRE),
                        org.assertj.core.groups.Tuple.tuple(MemorySubjectType.FACT, MemoryAction.RETIRE),
                        org.assertj.core.groups.Tuple.tuple(MemorySubjectType.FACT, MemoryAction.RETIRE),
                        org.assertj.core.groups.Tuple.tuple(MemorySubjectType.FACT, MemoryAction.RETIRE));
    }

    /**
     * The operator-visible consequence is already pinned where the wording lives: a decision on a
     * RETIRED fact answers the marker the card maps
     * ({@code MemoryProposalServiceTest:868-896}, {@code ALREADY_DECIDED}), and the sweep below is
     * what puts the fact into that state, so the card's Retry settles instead of 409-ing forever.
     */

    @Test
    void retireFactClosesTheValidityWindow() {
        UUID entityId = UUID.randomUUID();
        MemoryFact active = fact(entityId, FactStatus.ACTIVE);
        when(facts.findById(active.getId())).thenReturn(Optional.of(active));

        MemoryFact retired = service.retireFact(active.getId(), "operator-console", OPERATOR);

        assertThat(retired.getStatus()).isEqualTo(FactStatus.RETIRED);
        assertThat(retired.getValidTo()).isNotNull();
        verify(assertions).save(any(MemoryAssertion.class));
    }

    @Test
    void retireFactIsIdempotent() {
        MemoryFact retired = fact(UUID.randomUUID(), FactStatus.RETIRED);
        when(facts.findById(retired.getId())).thenReturn(Optional.of(retired));

        service.retireFact(retired.getId(), null, OPERATOR);

        verify(facts, never()).save(any());
        verify(assertions, never()).save(any());
    }

    /**
     * The write is flushed inside the transaction, so a concurrent change surfaces here as
     * the governed conflict with a fixed message — not at commit as a raw
     * {@code ObjectOptimisticLockingFailureException} whose Hibernate text names the entity
     * class and row id.
     */
    @Test
    void renameTranslatesConcurrentModificationToGovernedConflict() {
        UUID id = UUID.randomUUID();
        when(entities.findById(id)).thenReturn(Optional.of(entity(id, null)));
        doThrow(new ObjectOptimisticLockingFailureException(MemoryEntity.class, id))
                .when(entities).flush();

        assertThatThrownBy(() -> service.renameEntity(id, "Operator Prime", OPERATOR))
                .isInstanceOf(MemoryConflictException.class)
                .hasMessageContaining("re-read before retrying")
                .hasMessageNotContaining(MemoryEntity.class.getName())
                .hasMessageNotContaining(id.toString());
    }

    @Test
    void retireFactTranslatesConcurrentModificationToGovernedConflict() {
        UUID entityId = UUID.randomUUID();
        MemoryFact active = fact(entityId, FactStatus.ACTIVE);
        when(facts.findById(active.getId())).thenReturn(Optional.of(active));
        doThrow(new ObjectOptimisticLockingFailureException(MemoryFact.class, active.getId()))
                .when(facts).flush();

        assertThatThrownBy(() -> service.retireFact(active.getId(), "operator-console", OPERATOR))
                .isInstanceOf(MemoryConflictException.class)
                .hasMessageContaining("re-read before retrying")
                .hasMessageNotContaining(MemoryFact.class.getName())
                .hasMessageNotContaining(active.getId().toString());
    }

    @Test
    void workerCannotChangeTheLifecycle() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> service.renameEntity(id, "Nope", WORKER))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.retireFact(UUID.randomUUID(), null, WORKER))
                .isInstanceOf(SecurityException.class);
        verify(entities, never()).findById(any());
    }

    @Test
    void missingEntityIsNotFound() {
        UUID id = UUID.randomUUID();
        when(entities.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.retireEntity(id, OPERATOR))
                .isInstanceOf(MemoryNotFoundException.class);
    }
}
