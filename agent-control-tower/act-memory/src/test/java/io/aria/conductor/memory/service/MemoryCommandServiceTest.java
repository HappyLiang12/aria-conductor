package io.aria.conductor.memory.service;

import io.aria.conductor.common.event.MemoryProposalCreatedEvent;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.memory.dto.AddFactCommand;
import io.aria.conductor.memory.dto.AddRelationCommand;
import io.aria.conductor.memory.dto.CreateEntityCommand;
import io.aria.conductor.memory.dto.MemoryActor;
import io.aria.conductor.memory.dto.SupersedeFactCommand;
import io.aria.conductor.memory.model.EntityStatus;
import io.aria.conductor.memory.model.FactStatus;
import io.aria.conductor.memory.model.MemoryAction;
import io.aria.conductor.memory.model.MemoryActorType;
import io.aria.conductor.memory.model.MemoryAssertion;
import io.aria.conductor.memory.model.MemoryEntity;
import io.aria.conductor.memory.model.MemoryFact;
import io.aria.conductor.memory.model.MemoryKnowledgeLink;
import io.aria.conductor.memory.model.MemoryLinkStatus;
import io.aria.conductor.memory.model.MemoryOrigin;
import io.aria.conductor.memory.model.MemoryRelation;
import io.aria.conductor.memory.model.MemorySubjectType;
import io.aria.conductor.memory.repository.MemoryAssertionRepository;
import io.aria.conductor.memory.repository.MemoryEntityRepository;
import io.aria.conductor.memory.repository.MemoryFactRepository;
import io.aria.conductor.memory.repository.MemoryKnowledgeLinkRepository;
import io.aria.conductor.memory.repository.MemoryRelationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.description;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Governance matrix, supersede transaction, proposal idempotency, merge and the secret
 * guard of the memory write path — the plan's Lane B Task B-1 acceptance matrix.
 * <p>
 * The service is exercised against mocked repositories so the tests can assert what is
 * <em>not</em> written (no second fact, no assertion on a rejected write) as precisely as
 * what is. Persistence itself is covered by {@link MemoryCommandServiceTransactionTest}.
 */
@ExtendWith(MockitoExtension.class)
class MemoryCommandServiceTest {

    private static final MemoryActor OPERATOR = new MemoryActor(MemoryActorType.OPERATOR, "local");
    private static final MemoryActor EXTRACTION = new MemoryActor(MemoryActorType.EXTRACTION, "run-1");
    private static final Instant VALID_FROM = Instant.parse("2026-11-01T00:00:00Z");

    @Mock
    MemoryEntityRepository entities;

    @Mock
    MemoryFactRepository facts;

    @Mock
    MemoryRelationRepository relations;

    @Mock
    MemoryKnowledgeLinkRepository links;

    @Mock
    MemoryAssertionRepository assertions;

    @Mock
    MemoryProposalWriter proposalWriter;

    @Mock
    ApplicationEventPublisher events;

    MemoryCommandService command;

    private final MemoryValueGuard guard = new MemoryValueGuard();

    @BeforeEach
    void setUp() {
        command = new MemoryCommandService(entities, facts, relations, links, assertions, guard, proposalWriter,
                new MemoryCurrentValueSlot(entities), events);
        // The real writer persists the proposal in its own transaction; here it is delegated
        // so the tests can still assert exactly how many rows were written.
        lenient().when(proposalWriter.insertProposal(any(MemoryFact.class), any(MemoryAssertion.class)))
                .thenAnswer(invocation -> {
                    MemoryFact fact = invocation.getArgument(0);
                    facts.save(fact);
                    assertions.save(invocation.getArgument(1));
                    return fact;
                });
    }

    // ---------------------------------------------------------------- governance

    @Test
    void operatorFactsAreActiveAndExtractedFactsArePending() {
        UUID entityId = id("operator");
        when(entities.findById(entityId)).thenReturn(Optional.of(entity(entityId)));
        when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(entityId, FactStatus.ACTIVE))
                .thenReturn(List.of());
        when(facts.findByEntityIdOrderByValidFromDesc(entityId)).thenReturn(List.of());

        MemoryFact active = command.addFact(entityId,
                new AddFactCommand("timezone", "Asia/Hong_Kong", "STRING", Sensitivity.INTERNAL, null),
                OPERATOR);
        MemoryFact pending = command.addFact(entityId,
                new AddFactCommand("editor", "vim", "STRING", Sensitivity.INTERNAL, "run-1"),
                EXTRACTION);

        assertThat(active.getStatus()).isEqualTo(FactStatus.ACTIVE);
        assertThat(active.getOrigin()).isEqualTo(MemoryOrigin.OPERATOR);
        assertThat(active.getProposalHash()).isNull();
        assertThat(pending.getStatus()).isEqualTo(FactStatus.PENDING);
        assertThat(pending.getOrigin()).isEqualTo(MemoryOrigin.EXTRACTION);
        assertThat(pending.getProposalHash()).as("an extraction proposal carries its idempotency key").isNotBlank();
    }

    @ParameterizedTest
    @ValueSource(strings = {"repo", "branch", "issue", "stack", "workspace"})
    void runStructuralKeysAreActiveAndCarryProvenance(String key) {
        UUID entityId = id("repo");
        when(entities.findById(entityId)).thenReturn(Optional.of(entity(entityId)));
        when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(entityId, FactStatus.ACTIVE))
                .thenReturn(List.of());

        MemoryFact fact = command.addFact(entityId,
                new AddFactCommand(key, "aria-conductor", "STRING", Sensitivity.INTERNAL, "run-42"),
                new MemoryActor(MemoryActorType.RUN, "run-42"));

        assertThat(fact.getStatus()).isEqualTo(FactStatus.ACTIVE);
        assertThat(fact.getOrigin()).isEqualTo(MemoryOrigin.RUN);
        MemoryAssertion assertion = captureAssertions(1).get(0);
        assertThat(assertion.getActorType()).isEqualTo(MemoryActorType.RUN);
        assertThat(assertion.getSourceRef()).as("provenance is recorded on the audit row").isEqualTo("run-42");
    }

    @Test
    void aRunCannotWriteANonStructuralKey() {
        assertThatThrownBy(() -> command.addFact(id("person"),
                new AddFactCommand("favorite_color", "blue", "STRING", Sensitivity.INTERNAL, "run-42"),
                new MemoryActor(MemoryActorType.RUN, "run-42")))
                .isInstanceOf(MemoryValidationException.class)
                .hasMessageContaining("favorite_color")
                .hasMessageContaining("repo");
        verifyNoInteractions(entities, facts, relations, assertions);
    }

    @Test
    void aRunWriteWithoutAProvenanceReferenceIsRejected() {
        assertThatThrownBy(() -> command.addFact(id("repo"),
                new AddFactCommand("repo", "aria-conductor", "STRING", Sensitivity.INTERNAL, null),
                new MemoryActor(MemoryActorType.RUN, "run-42")))
                .isInstanceOf(MemoryValidationException.class)
                .hasMessageContaining("sourceRef");
        verifyNoInteractions(entities, facts, relations, assertions);
    }

    @Test
    void aSystemActorCannotWriteMemory() {
        assertThatThrownBy(() -> command.addFact(id("person"),
                new AddFactCommand("timezone", "UTC", "STRING", Sensitivity.INTERNAL, null),
                new MemoryActor(MemoryActorType.SYSTEM, "scheduler")))
                .isInstanceOf(MemoryValidationException.class)
                .hasMessageContaining("SYSTEM");
        verifyNoInteractions(entities, facts, relations, assertions);
    }

    @Test
    void addFactOnAMissingEntityIsNotFound() {
        UUID entityId = id("gone");
        when(entities.findById(entityId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> command.addFact(entityId,
                new AddFactCommand("timezone", "UTC", "STRING", Sensitivity.INTERNAL, null), OPERATOR))
                .isInstanceOf(MemoryNotFoundException.class)
                .hasMessageContaining(entityId.toString());
        verify(facts, never()).save(any());
        verify(assertions, never()).save(any());
    }

    // ---------------------------------------------------------------- pending entities

    /**
     * A PENDING entity is not memory yet, so an ACTIVE fact may not hang off it: the fact would
     * be current while its parent is invisible to recall, and approving the entity later would
     * silently publish a value nobody proposed as a proposal.
     */
    @Test
    void anActiveFactCannotBeAttachedToAPendingEntity() {
        UUID pendingEntityId = id("pending-person");
        MemoryEntity pending = entity(pendingEntityId);
        pending.setStatus(EntityStatus.PENDING);
        when(entities.findById(pendingEntityId)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> command.addFact(pendingEntityId,
                new AddFactCommand("timezone", "Asia/Hong_Kong", "STRING", Sensitivity.INTERNAL, "operator message"),
                new MemoryActor(MemoryActorType.OPERATOR, "operator-session")))
                .isInstanceOf(MemoryConflictException.class)
                .hasMessageContaining("PENDING");
        verify(facts, never()).save(any());
        verify(assertions, never()).save(any());
    }

    /**
     * T29 Item 2 (P2). The dependent-read guard refused only a PENDING parent, so an ACTIVE write
     * against a RETIRED or MERGED entity still landed — invisible current memory, because recall
     * matches ACTIVE entities only, the context contributor reads through recall and the default
     * list is ACTIVE-only. Merge moves the source's facts, so a fact added to a MERGED source is
     * not reachable from the survivor either. The invariant is "current memory has an ACTIVE
     * parent", not "not PENDING"; the positive control is
     * {@link #anActiveFactMayStillBeAttachedToAnActiveParent}.
     */
    @ParameterizedTest
    @ValueSource(strings = {"RETIRED", "MERGED"})
    void anActiveFactCannotBeAttachedToANonActiveParent(String parentStatus) {
        UUID entityId = id("person:" + parentStatus);
        MemoryEntity parent = entity(entityId);
        parent.setStatus(EntityStatus.valueOf(parentStatus));
        when(entities.findById(entityId)).thenReturn(Optional.of(parent));

        assertThatThrownBy(() -> command.addFact(entityId,
                new AddFactCommand("timezone", "Asia/Hong_Kong", "STRING", Sensitivity.INTERNAL,
                        "operator message"),
                new MemoryActor(MemoryActorType.OPERATOR, "operator-session")))
                .as("a fact may only become current under an ACTIVE parent")
                .isInstanceOf(MemoryConflictException.class)
                .hasMessageContaining(parentStatus);

        verify(facts, never()).save(any());
        verify(assertions, never()).save(any());
    }

    /** The positive control: an ACTIVE parent still takes the same operator write. */
    @Test
    void anActiveFactMayStillBeAttachedToAnActiveParent() {
        UUID entityId = id("person:active-parent");
        when(entities.findById(entityId)).thenReturn(Optional.of(entity(entityId)));
        when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(entityId, FactStatus.ACTIVE))
                .thenReturn(List.of());

        MemoryFact fact = command.addFact(entityId,
                new AddFactCommand("timezone", "Asia/Hong_Kong", "STRING", Sensitivity.INTERNAL, null),
                new MemoryActor(MemoryActorType.OPERATOR, "operator-session"));

        assertThat(fact.getStatus()).isEqualTo(FactStatus.ACTIVE);
        verify(facts).save(fact);
    }

    /**
     * The RUN-actor structural-fact path writes ACTIVE facts ({@code statusOf}: RUN yields
     * ACTIVE), so the same invariant has to refuse it: a run must not park current memory under a
     * dead parent either.
     */
    @Test
    void aRunStructuralFactCannotBeAttachedToARetiredParent() {
        UUID entityId = id("repo:retired");
        MemoryEntity parent = entity(entityId);
        parent.setStatus(EntityStatus.RETIRED);
        when(entities.findById(entityId)).thenReturn(Optional.of(parent));

        assertThatThrownBy(() -> command.addFact(entityId,
                new AddFactCommand("repo", "aria-conductor", "STRING", Sensitivity.INTERNAL, "run-42"),
                new MemoryActor(MemoryActorType.RUN, "run-42")))
                .isInstanceOf(MemoryConflictException.class)
                .hasMessageContaining("RETIRED");

        verify(facts, never()).save(any());
        verify(assertions, never()).save(any());
    }

    /** The positive control for the guard: a PENDING proposal may still depend on a PENDING entity. */
    @Test
    void aPendingFactMayHangOffAPendingEntity() {
        UUID pendingEntityId = id("pending-person");
        MemoryEntity pending = entity(pendingEntityId);
        pending.setStatus(EntityStatus.PENDING);
        when(entities.findById(pendingEntityId)).thenReturn(Optional.of(pending));
        when(facts.findByEntityIdOrderByValidFromDesc(pendingEntityId)).thenReturn(List.of());

        MemoryFact fact = command.addFact(pendingEntityId,
                new AddFactCommand("timezone", "Asia/Hong_Kong", "STRING", Sensitivity.INTERNAL, "run-1"),
                EXTRACTION);

        assertThat(fact.getStatus()).isEqualTo(FactStatus.PENDING);
        assertThat(fact.getOrigin()).isEqualTo(MemoryOrigin.EXTRACTION);
        verify(facts).save(any(MemoryFact.class));
    }

    /**
     * The composition of two rules reviewed as verified-correct but untested (T14 review m3):
     * {@link MemoryCommandService#createEntity} returns an existing PENDING row unchanged, then the
     * OPERATOR half of the unknown-key shape computes an ACTIVE fact and the dependent-read guard
     * refuses it. The proposal stays PENDING and no fact or assertion is written.
     */
    @Test
    void anOperatorUnknownKeyOnAnExistingPendingEntityConflictsAndLeavesItPending() {
        MemoryEntity pending = entity(id("pending-person"));
        pending.setCanonicalKey("person:happy");
        pending.setStatus(EntityStatus.PENDING);
        when(entities.findByKindAndCanonicalKey("PERSON", "person:happy")).thenReturn(Optional.of(pending));
        when(entities.findById(pending.getId())).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> command.addFactByKey("PERSON", "person:happy",
                new AddFactCommand("preferred_editor", "IntelliJ IDEA", "STRING", Sensitivity.INTERNAL, null),
                new MemoryActor(MemoryActorType.OPERATOR, "operator-session")))
                .isInstanceOf(MemoryConflictException.class)
                .hasMessageContaining("ACTIVE fact cannot be");

        assertThat(pending.getStatus())
                .as("the existing PENDING proposal is returned unchanged, never promoted")
                .isEqualTo(EntityStatus.PENDING);
        verify(entities, never()).save(any());
        verify(facts, never()).save(any());
        verify(assertions, never()).save(any());
    }

    // ---------------------------------------------------------------- conflicts

    @Test
    void aSecondActiveFactForKeyConflicts() {
        UUID entityId = id("person");
        when(entities.findById(entityId)).thenReturn(Optional.of(entity(entityId)));
        MemoryFact current = currentFact(id("f1"), entityId, "timezone", "UTC", 3);
        when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(entityId, FactStatus.ACTIVE))
                .thenReturn(List.of(current));

        assertThatThrownBy(() -> command.addFact(entityId,
                new AddFactCommand("timezone", "Asia/Hong_Kong", "STRING", Sensitivity.INTERNAL, null), OPERATOR))
                .isInstanceOf(MemoryConflictException.class)
                .hasMessageContaining("timezone");

        verify(facts, never()).save(any());
        verify(assertions, never()).save(any());
    }

    // ---------------------------------------------------------------- supersede

    @Test
    void supersedeWithTheCurrentVersionRetiresTheOldFactAndWritesBothAssertions() {
        UUID entityId = id("person");
        MemoryFact old = currentFact(id("f1"), entityId, "timezone", "UTC", 3);
        when(facts.findById(old.getId())).thenReturn(Optional.of(old));
        when(entities.findById(entityId)).thenReturn(Optional.of(entity(entityId)));

        MemoryFact fresh = command.supersedeFact(old.getId(),
                new SupersedeFactCommand("Asia/Hong_Kong", 3, "run-9"), OPERATOR);

        assertThat(old.getStatus()).isEqualTo(FactStatus.RETIRED);
        assertThat(old.getValidTo()).as("the closed row records when it stopped being current").isNotNull();
        assertThat(fresh.getId()).isNotNull();
        assertThat(fresh.getStatus()).isEqualTo(FactStatus.ACTIVE);
        assertThat(fresh.getEntityId()).isEqualTo(entityId);
        assertThat(fresh.getFactKey()).isEqualTo("timezone");
        assertThat(fresh.getValueText()).isEqualTo("Asia/Hong_Kong");
        assertThat(fresh.getValueType()).isEqualTo("STRING");
        assertThat(fresh.getSensitivity()).isEqualTo(Sensitivity.INTERNAL);

        ArgumentCaptor<MemoryFact> saved = ArgumentCaptor.forClass(MemoryFact.class);
        verify(facts, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).containsExactly(old, fresh);

        List<MemoryAssertion> written = captureAssertions(2);
        assertThat(written).extracting(MemoryAssertion::getAction)
                .containsExactlyInAnyOrder(MemoryAction.SUPERSEDE, MemoryAction.CREATE);
        assertThat(written).extracting(MemoryAssertion::getSubjectId)
                .containsExactlyInAnyOrder(old.getId(), fresh.getId());
    }

    @Test
    void aStaleSupersedeIsAConflictAndWritesNothing() {
        UUID entityId = id("person");
        MemoryFact old = currentFact(id("f1"), entityId, "timezone", "UTC", 3);
        when(facts.findById(old.getId())).thenReturn(Optional.of(old));

        assertThatThrownBy(() -> command.supersedeFact(old.getId(),
                new SupersedeFactCommand("Asia/Hong_Kong", 2, null), OPERATOR))
                .isInstanceOf(MemoryConflictException.class)
                .hasMessageContaining("version");

        assertThat(old.getValidTo()).isNull();
        verify(facts, never()).save(any());
        verify(assertions, never()).save(any());
    }

    @Test
    void supersedingAClosedFactIsAConflict() {
        UUID entityId = id("person");
        MemoryFact closed = currentFact(id("f1"), entityId, "timezone", "UTC", 3);
        closed.setStatus(FactStatus.RETIRED);
        closed.setValidTo(VALID_FROM.plusSeconds(60));
        when(facts.findById(closed.getId())).thenReturn(Optional.of(closed));

        assertThatThrownBy(() -> command.supersedeFact(closed.getId(),
                new SupersedeFactCommand("Asia/Hong_Kong", 3, null), OPERATOR))
                .isInstanceOf(MemoryConflictException.class);
        verify(facts, never()).save(any());
    }

    @Test
    void extractionCannotSupersede() {
        assertThatThrownBy(() -> command.supersedeFact(id("f1"),
                new SupersedeFactCommand("Asia/Hong_Kong", 3, "run-1"), EXTRACTION))
                .isInstanceOf(MemoryValidationException.class);
        verifyNoInteractions(entities, facts, relations, assertions);
    }

    /**
     * A supersede inserts a new current value, so it must claim the entity slot the same way
     * an addFact does. Losing that claim — an addFact (or an approval) bumped the entity
     * version first — has to surface as the governed conflict, never as a raw
     * {@code OptimisticLockingFailureException}.
     */
    @Test
    void aSupersedeThatLosesTheEntitySlotIsAGovernedConflict() {
        UUID entityId = id("person");
        MemoryFact old = currentFact(id("f1"), entityId, "timezone", "UTC", 3);
        when(facts.findById(old.getId())).thenReturn(Optional.of(old));
        when(entities.findById(entityId)).thenReturn(Optional.of(entity(entityId)));
        when(entities.save(any(MemoryEntity.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(MemoryEntity.class, entityId));

        assertThatThrownBy(() -> command.supersedeFact(old.getId(),
                new SupersedeFactCommand("Asia/Hong_Kong", 3, null), OPERATOR))
                .as("a lost slot is a conflict, not a 500")
                .isInstanceOf(MemoryConflictException.class)
                .hasMessageContaining("timezone");

        assertThat(old.getStatus()).as("the loser writes no closed row").isEqualTo(FactStatus.ACTIVE);
        assertThat(old.getValidTo()).isNull();
        verify(facts, never()).save(any());
        verify(assertions, never()).save(any());
    }

    /**
     * The fact row carries its own {@code @Version}: two supersedes of the same row can pass
     * the service's expected-version check and still collide on the UPDATE. That loss is
     * raised by the flush, and it must be translated in the same method instead of escaping
     * at commit.
     */
    @Test
    void aSupersedeWhoseRowVersionWasChangedUnderneathIsAGovernedConflict() {
        UUID entityId = id("person");
        MemoryFact old = currentFact(id("f1"), entityId, "timezone", "UTC", 3);
        when(facts.findById(old.getId())).thenReturn(Optional.of(old));
        when(entities.findById(entityId)).thenReturn(Optional.of(entity(entityId)));
        doThrow(new ObjectOptimisticLockingFailureException(MemoryFact.class, old.getId()))
                .when(facts).flush();

        assertThatThrownBy(() -> command.supersedeFact(old.getId(),
                new SupersedeFactCommand("Asia/Hong_Kong", 3, null), OPERATOR))
                .as("a row-version loss is the governed 409, not a raw exception")
                .isInstanceOf(MemoryConflictException.class)
                .hasMessageContaining("timezone");

        verify(assertions, never()).save(any());
    }

    // ---------------------------------------------------------------- retire

    @Test
    void retireClosesTheCurrentValueAndWritesTheRetireAssertion() {
        UUID entityId = id("person");
        MemoryFact current = currentFact(id("f1"), entityId, "timezone", "UTC", 3);
        when(facts.findById(current.getId())).thenReturn(Optional.of(current));

        MemoryFact retired = command.retireFact(current.getId(), OPERATOR);

        assertThat(retired).isSameAs(current);
        assertThat(current.getStatus()).isEqualTo(FactStatus.RETIRED);
        assertThat(current.getValidTo()).as("the closed row records when it stopped being current").isNotNull();
        verify(facts).save(current);

        List<MemoryAssertion> written = captureAssertions(1);
        assertThat(written).singleElement().satisfies(assertion -> {
            assertThat(assertion.getAction()).isEqualTo(MemoryAction.RETIRE);
            assertThat(assertion.getSubjectType()).isEqualTo(MemorySubjectType.FACT);
            assertThat(assertion.getSubjectId()).isEqualTo(current.getId());
            assertThat(assertion.getActorType()).isEqualTo(MemoryActorType.OPERATOR);
            assertThat(assertion.getBeforeJson()).contains("ACTIVE").contains("timezone");
            assertThat(assertion.getAfterJson()).contains("RETIRED").contains("timezone");
        });
    }

    @Test
    void retiringAClosedFactIsAConflictAndWritesNothing() {
        UUID entityId = id("person");
        MemoryFact closed = currentFact(id("f1"), entityId, "timezone", "UTC", 3);
        closed.setStatus(FactStatus.RETIRED);
        closed.setValidTo(VALID_FROM.plusSeconds(60));
        when(facts.findById(closed.getId())).thenReturn(Optional.of(closed));

        assertThatThrownBy(() -> command.retireFact(closed.getId(), OPERATOR))
                .isInstanceOf(MemoryConflictException.class)
                .hasMessageContaining(closed.getId().toString());

        assertThat(closed.getValidTo()).as("the closure is not rewritten").isEqualTo(VALID_FROM.plusSeconds(60));
        verify(facts, never()).save(any());
        verify(assertions, never()).save(any());
    }

    /**
     * The fact row carries its own {@code @Version}: two retires of the same row can both
     * pass the status check and still collide on the UPDATE. That loss is raised by the
     * flush and must be translated in the method, before the assertion is written, so the
     * caller sees the governed conflict and no partial retire is left behind.
     */
    @Test
    void aRetireWhoseRowVersionWasChangedUnderneathIsAGovernedConflict() {
        UUID entityId = id("person");
        MemoryFact current = currentFact(id("f1"), entityId, "timezone", "UTC", 3);
        when(facts.findById(current.getId())).thenReturn(Optional.of(current));
        doThrow(new ObjectOptimisticLockingFailureException(MemoryFact.class, current.getId()))
                .when(facts).flush();

        assertThatThrownBy(() -> command.retireFact(current.getId(), OPERATOR))
                .as("a row-version loss is the governed 409, not a raw exception")
                .isInstanceOf(MemoryConflictException.class)
                .hasMessageContaining("timezone");

        verify(assertions, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXTRACTION", "RUN"})
    void retireIsOperatorOnly(String actorType) {
        assertThatThrownBy(() -> command.retireFact(id("f1"),
                new MemoryActor(MemoryActorType.valueOf(actorType), "run-1")))
                .isInstanceOf(SecurityException.class);
        verifyNoInteractions(facts, assertions);
    }

    @Test
    void retiringAMissingFactIsNotFound() {
        UUID missing = id("gone");
        when(facts.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> command.retireFact(missing, OPERATOR))
                .isInstanceOf(MemoryNotFoundException.class)
                .hasMessageContaining(missing.toString());
        verify(assertions, never()).save(any());
    }

    // ---------------------------------------------------------------- proposals

    @Test
    void aRepeatedProposalReturnsTheExistingRowWithoutASecondWrite() {
        UUID entityId = id("person");
        when(entities.findById(entityId)).thenReturn(Optional.of(entity(entityId)));
        MemoryFact[] stored = new MemoryFact[1];
        when(facts.findByEntityIdOrderByValidFromDesc(entityId))
                .thenAnswer(invocation -> stored[0] == null ? List.of() : List.of(stored[0]));
        when(facts.save(any(MemoryFact.class))).thenAnswer(invocation -> {
            stored[0] = invocation.getArgument(0);
            return stored[0];
        });

        AddFactCommand proposal = new AddFactCommand("editor", "vim", "STRING", Sensitivity.INTERNAL, "run-1");
        MemoryFact first = command.addFact(entityId, proposal, EXTRACTION);
        MemoryFact second = command.addFact(entityId, proposal, EXTRACTION);

        assertThat(second.getId()).isEqualTo(first.getId());
        verify(facts, times(1)).save(any(MemoryFact.class));
        verify(assertions, times(1)).save(any(MemoryAssertion.class));
    }

    @Test
    void theProposalHashCoversOriginEntityKeyValueAndSource() throws Exception {
        UUID entityId = id("person");
        when(entities.findById(entityId)).thenReturn(Optional.of(entity(entityId)));
        when(facts.findByEntityIdOrderByValidFromDesc(entityId)).thenReturn(List.of());

        MemoryFact fact = command.addFact(entityId,
                new AddFactCommand("editor", "vim", "STRING", Sensitivity.INTERNAL, "run-1"), EXTRACTION);

        String material = "EXTRACTION|" + entityId + "|editor|vim|run-1";
        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)));
        assertThat(fact.getProposalHash()).isEqualTo(expected);
    }

    @Test
    void aDifferentProposalValueIsNotDeduplicated() {
        UUID entityId = id("person");
        when(entities.findById(entityId)).thenReturn(Optional.of(entity(entityId)));
        MemoryFact[] stored = new MemoryFact[1];
        when(facts.findByEntityIdOrderByValidFromDesc(entityId))
                .thenAnswer(invocation -> stored[0] == null ? List.of() : List.of(stored[0]));
        when(facts.save(any(MemoryFact.class))).thenAnswer(invocation -> {
            stored[0] = invocation.getArgument(0);
            return stored[0];
        });

        MemoryFact first = command.addFact(entityId,
                new AddFactCommand("editor", "vim", "STRING", Sensitivity.INTERNAL, "run-1"), EXTRACTION);
        MemoryFact second = command.addFact(entityId,
                new AddFactCommand("editor", "nano", "STRING", Sensitivity.INTERNAL, "run-1"), EXTRACTION);

        assertThat(second.getId()).isNotEqualTo(first.getId());
        verify(facts, times(2)).save(any(MemoryFact.class));
    }

    @Test
    void theLoserOfAProposalRaceReturnsTheWinnersRowInsteadOfFailing() {
        UUID entityId = id("person");
        when(entities.findById(entityId)).thenReturn(Optional.of(entity(entityId)));
        when(facts.findByEntityIdOrderByValidFromDesc(entityId)).thenReturn(List.of());
        MemoryFact winner = MemoryFact.builder()
                .id(id("f-winner"))
                .entityId(entityId)
                .factKey("editor")
                .valueText("vim")
                .valueType("STRING")
                .status(FactStatus.PENDING)
                .origin(MemoryOrigin.EXTRACTION)
                .sensitivity(Sensitivity.INTERNAL)
                .proposalHash("hash-of-the-same-proposal")
                .validFrom(VALID_FROM)
                .build();
        when(proposalWriter.insertProposal(any(MemoryFact.class), any(MemoryAssertion.class)))
                .thenThrow(new DataIntegrityViolationException("uq_memory_fact_proposal violated"));
        when(proposalWriter.findExistingProposal(eq(entityId), any()))
                .thenReturn(Optional.of(winner));

        MemoryFact returned = command.addFact(entityId,
                new AddFactCommand("editor", "vim", "STRING", Sensitivity.INTERNAL, "run-1"), EXTRACTION);

        assertThat(returned.getId())
                .as("a duplicate proposal is a no-op that returns the stored row")
                .isEqualTo(winner.getId());
        verify(facts, never()).save(any());
        verify(assertions, never()).save(any());
    }

    @Test
    void aProposalRaceWithoutAReadableWinnerIsStillAGovernedResult() {
        UUID entityId = id("person");
        when(entities.findById(entityId)).thenReturn(Optional.of(entity(entityId)));
        when(facts.findByEntityIdOrderByValidFromDesc(entityId)).thenReturn(List.of());
        when(proposalWriter.insertProposal(any(MemoryFact.class), any(MemoryAssertion.class)))
                .thenThrow(new DataIntegrityViolationException("uq_memory_fact_proposal violated"));
        when(proposalWriter.findExistingProposal(eq(entityId), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> command.addFact(entityId,
                new AddFactCommand("editor", "vim", "STRING", Sensitivity.INTERNAL, "run-1"), EXTRACTION))
                .as("never surface a raw database error to the memory API")
                .isInstanceOf(MemoryConflictException.class)
                .hasMessageContaining("editor");
    }

    // ---------------------------------------------------------------- merge

    @Test
    void mergeRepointsFactsAndRelationsAndWritesAMergeAssertion() {
        UUID sourceId = id("duplicate-operator");
        UUID targetId = id("operator");
        MemoryEntity source = entity(sourceId);
        when(entities.findById(sourceId)).thenReturn(Optional.of(source));
        when(entities.findById(targetId)).thenReturn(Optional.of(entity(targetId)));

        MemoryFact fact = currentFact(id("f1"), sourceId, "timezone", "UTC", 1);
        when(facts.findByEntityIdOrderByValidFromDesc(sourceId)).thenReturn(List.of(fact));
        MemoryRelation outgoing = relation(id("r1"), sourceId, "OWNS", id("conductor"));
        MemoryRelation incoming = relation(id("r2"), id("conductor"), "WORKS_ON", sourceId);
        when(relations.findByFromEntityIdAndStatus(sourceId, FactStatus.ACTIVE)).thenReturn(List.of(outgoing));
        when(relations.findByFromEntityIdAndStatus(sourceId, FactStatus.PENDING)).thenReturn(List.of());
        when(relations.findByToEntityIdAndStatus(sourceId, FactStatus.ACTIVE)).thenReturn(List.of(incoming));
        when(relations.findByToEntityIdAndStatus(sourceId, FactStatus.PENDING)).thenReturn(List.of());

        MemoryEntity merged = command.mergeEntities(sourceId, targetId, OPERATOR);

        assertThat(merged.getStatus()).isEqualTo(EntityStatus.MERGED);
        assertThat(merged.getMergedInto()).isEqualTo(targetId);
        assertThat(fact.getEntityId()).isEqualTo(targetId);
        assertThat(outgoing.getFromEntityId()).isEqualTo(targetId);
        assertThat(incoming.getToEntityId()).isEqualTo(targetId);
        verify(facts).save(fact);
        verify(relations).save(outgoing);
        verify(relations).save(incoming);
        verify(entities).save(source);

        MemoryAssertion assertion = captureAssertions(1).get(0);
        assertThat(assertion.getSubjectType()).isEqualTo(MemorySubjectType.ENTITY);
        assertThat(assertion.getSubjectId()).isEqualTo(sourceId);
        assertThat(assertion.getAction()).isEqualTo(MemoryAction.MERGE);
    }

    @Test
    void mergeRepointsKnowledgeLinksAndDropsTheSourceLinkOnADuplicate() {
        UUID sourceId = id("duplicate-operator");
        UUID targetId = id("operator");
        when(entities.findById(sourceId)).thenReturn(Optional.of(entity(sourceId)));
        when(entities.findById(targetId)).thenReturn(Optional.of(entity(targetId)));

        UUID sharedKnowledge = id("knowledge-shared");
        UUID sourceOnlyKnowledge = id("knowledge-source-only");
        MemoryKnowledgeLink survivorLink = link(id("l-survivor"), targetId, sharedKnowledge);
        MemoryKnowledgeLink duplicateSourceLink = link(id("l-duplicate"), sourceId, sharedKnowledge);
        MemoryKnowledgeLink sourceOnlyLink = link(id("l-source-only"), sourceId, sourceOnlyKnowledge);
        when(links.findByEntityId(targetId)).thenReturn(List.of(survivorLink));
        when(links.findByEntityId(sourceId)).thenReturn(List.of(duplicateSourceLink, sourceOnlyLink));

        command.mergeEntities(sourceId, targetId, OPERATOR);

        assertThat(sourceOnlyLink.getEntityId())
                .as("a link only the source had moves to the survivor")
                .isEqualTo(targetId);
        verify(links).save(sourceOnlyLink);
        assertThat(survivorLink.getEntityId()).as("the survivor's own link is untouched").isEqualTo(targetId);
        verify(links, never()).delete(survivorLink);
        verify(links, never()).save(survivorLink);
        verify(links, description(
                "the duplicate source link is dropped, so uq_memory_knowledge_link cannot be violated"))
                .delete(duplicateSourceLink);
        verify(links, never()).save(duplicateSourceLink);
    }

    /**
     * The survivor has only RETIRED the knowledge item while the source holds the approved
     * ACTIVE decision. Deduping on {@code knowledgeItemId} alone would delete the approved
     * row and silently discard the decision, so the stronger status must win (ACTIVE >
     * PROPOSED > RETIRED) and only the weaker row may be dropped.
     */
    @Test
    void mergeKeepsTheApprovedSourceLinkOverARetiredSurvivorLink() {
        UUID sourceId = id("duplicate-operator");
        UUID targetId = id("operator");
        when(entities.findById(sourceId)).thenReturn(Optional.of(entity(sourceId)));
        when(entities.findById(targetId)).thenReturn(Optional.of(entity(targetId)));

        UUID sharedKnowledge = id("knowledge-shared");
        MemoryKnowledgeLink retiredOnSurvivor = link(id("l-survivor"), targetId, sharedKnowledge);
        retiredOnSurvivor.setStatus(MemoryLinkStatus.RETIRED);
        MemoryKnowledgeLink activeOnSource = link(id("l-source"), sourceId, sharedKnowledge);
        activeOnSource.setStatus(MemoryLinkStatus.ACTIVE);
        when(links.findByEntityId(targetId)).thenReturn(List.of(retiredOnSurvivor));
        when(links.findByEntityId(sourceId)).thenReturn(List.of(activeOnSource));

        command.mergeEntities(sourceId, targetId, OPERATOR);

        assertThat(activeOnSource.getEntityId())
                .as("the approved decision survives the merge")
                .isEqualTo(targetId);
        assertThat(activeOnSource.getStatus()).isEqualTo(MemoryLinkStatus.ACTIVE);
        verify(links).save(activeOnSource);
        verify(links, description("only the weaker RETIRED row is dropped")).delete(retiredOnSurvivor);
        verify(links, never()).delete(activeOnSource);
        verify(links, never()).save(retiredOnSurvivor);
        assertThat(captureAssertions(1).get(0).getAfterJson())
                .as("the dropped decision is recorded on the merge assertion")
                .contains("linksDroppedIds")
                .contains(retiredOnSurvivor.getId().toString());
    }

    /** The mirrored direction: the survivor's ACTIVE decision wins and the source row is dropped. */
    @Test
    void mergeKeepsTheActiveSurvivorLinkOverARetiredSourceLink() {
        UUID sourceId = id("duplicate-operator");
        UUID targetId = id("operator");
        when(entities.findById(sourceId)).thenReturn(Optional.of(entity(sourceId)));
        when(entities.findById(targetId)).thenReturn(Optional.of(entity(targetId)));

        UUID sharedKnowledge = id("knowledge-shared");
        MemoryKnowledgeLink activeOnSurvivor = link(id("l-survivor"), targetId, sharedKnowledge);
        activeOnSurvivor.setStatus(MemoryLinkStatus.ACTIVE);
        MemoryKnowledgeLink retiredOnSource = link(id("l-source"), sourceId, sharedKnowledge);
        retiredOnSource.setStatus(MemoryLinkStatus.RETIRED);
        when(links.findByEntityId(targetId)).thenReturn(List.of(activeOnSurvivor));
        when(links.findByEntityId(sourceId)).thenReturn(List.of(retiredOnSource));

        command.mergeEntities(sourceId, targetId, OPERATOR);

        assertThat(activeOnSurvivor.getEntityId()).isEqualTo(targetId);
        verify(links, never()).delete(activeOnSurvivor);
        verify(links, never()).save(activeOnSurvivor);
        verify(links).delete(retiredOnSource);
        verify(links, never()).save(retiredOnSource);
        assertThat(captureAssertions(1).get(0).getAfterJson())
                .contains("linksDroppedIds")
                .contains(retiredOnSource.getId().toString());
    }

    /**
     * PROPOSED outranks RETIRED. ACTIVE is not the only stronger status, so the rank must be
     * explicit: the enum declares PROPOSED, ACTIVE, RETIRED, and its ordinal order is not
     * the strength order.
     */
    @Test
    void mergeKeepsAProposedSourceLinkOverARetiredSurvivorLink() {
        UUID sourceId = id("duplicate-operator");
        UUID targetId = id("operator");
        when(entities.findById(sourceId)).thenReturn(Optional.of(entity(sourceId)));
        when(entities.findById(targetId)).thenReturn(Optional.of(entity(targetId)));

        UUID sharedKnowledge = id("knowledge-shared");
        MemoryKnowledgeLink retiredOnSurvivor = link(id("l-survivor"), targetId, sharedKnowledge);
        retiredOnSurvivor.setStatus(MemoryLinkStatus.RETIRED);
        MemoryKnowledgeLink proposedOnSource = link(id("l-source"), sourceId, sharedKnowledge);
        proposedOnSource.setStatus(MemoryLinkStatus.PROPOSED);
        when(links.findByEntityId(targetId)).thenReturn(List.of(retiredOnSurvivor));
        when(links.findByEntityId(sourceId)).thenReturn(List.of(proposedOnSource));

        command.mergeEntities(sourceId, targetId, OPERATOR);

        assertThat(proposedOnSource.getEntityId()).isEqualTo(targetId);
        verify(links).save(proposedOnSource);
        verify(links).delete(retiredOnSurvivor);
        verify(links, never()).delete(proposedOnSource);
    }

    @Test
    void mergeRepointsClosedAndRetiredRelationsSoHistoryFollowsTheSurvivor() {        UUID sourceId = id("duplicate-operator");
        UUID targetId = id("operator");
        when(entities.findById(sourceId)).thenReturn(Optional.of(entity(sourceId)));
        when(entities.findById(targetId)).thenReturn(Optional.of(entity(targetId)));

        MemoryRelation retired = relation(id("r-retired"), sourceId, "WORKS_ON", id("conductor"));
        retired.setStatus(FactStatus.RETIRED);
        retired.setValidTo(VALID_FROM.plusSeconds(60));
        when(relations.findByFromEntityIdAndStatus(eq(sourceId), any(FactStatus.class)))
                .thenAnswer(invocation -> invocation.getArgument(1) == FactStatus.RETIRED
                        ? List.of(retired)
                        : List.of());

        command.mergeEntities(sourceId, targetId, OPERATOR);

        assertThat(retired.getStatus())
                .as("provenance rule: re-point every row, so closed history stays with the survivor")
                .isEqualTo(FactStatus.RETIRED);
        assertThat(retired.getFromEntityId()).isEqualTo(targetId);
        assertThat(retired.getValidTo()).isEqualTo(VALID_FROM.plusSeconds(60));
        verify(relations).save(retired);
    }

    @Test
    void mergeClosesTheIncomingFactWhenTheSurvivorAlreadyHoldsTheKey() {
        UUID sourceId = id("duplicate-operator");
        UUID targetId = id("operator");
        when(entities.findById(sourceId)).thenReturn(Optional.of(entity(sourceId)));
        when(entities.findById(targetId)).thenReturn(Optional.of(entity(targetId)));

        MemoryFact incoming = currentFact(id("f-source"), sourceId, "timezone", "UTC", 1);
        MemoryFact survivor = currentFact(id("f-target"), targetId, "timezone", "Asia/Hong_Kong", 1);
        when(facts.findByEntityIdOrderByValidFromDesc(sourceId)).thenReturn(List.of(incoming));
        when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(targetId, FactStatus.ACTIVE))
                .thenReturn(List.of(survivor));

        command.mergeEntities(sourceId, targetId, OPERATOR);

        assertThat(survivor.getStatus()).isEqualTo(FactStatus.ACTIVE);
        assertThat(survivor.getValidTo()).as("the survivor's current value stays current").isNull();
        assertThat(incoming.getStatus())
                .as("the incoming value is superseded, never silently overwritten")
                .isEqualTo(FactStatus.RETIRED);
        assertThat(incoming.getValidTo()).isNotNull();
        assertThat(incoming.getEntityId()).isEqualTo(targetId);

        List<MemoryAssertion> written = captureAssertions(2);
        assertThat(written).extracting(MemoryAssertion::getAction)
                .as("the collision is audited as an explicit supersede")
                .containsExactlyInAnyOrder(MemoryAction.MERGE, MemoryAction.SUPERSEDE);
        assertThat(written).filteredOn(assertion -> assertion.getAction() == MemoryAction.SUPERSEDE)
                .singleElement()
                .satisfies(assertion -> {
                    assertThat(assertion.getSubjectId()).isEqualTo(incoming.getId());
                    assertThat(assertion.getAfterJson()).contains("RETIRED").contains(survivor.getId().toString());
                });
    }

    @Test
    void aMergeDoesNotCloseAKeyTheSurvivorHasOnlyAsHistory() {
        UUID sourceId = id("duplicate-operator");
        UUID targetId = id("operator");
        when(entities.findById(sourceId)).thenReturn(Optional.of(entity(sourceId)));
        when(entities.findById(targetId)).thenReturn(Optional.of(entity(targetId)));

        MemoryFact incoming = currentFact(id("f-source"), sourceId, "timezone", "UTC", 1);
        when(facts.findByEntityIdOrderByValidFromDesc(sourceId)).thenReturn(List.of(incoming));
        when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(targetId, FactStatus.ACTIVE))
                .thenReturn(List.of());

        command.mergeEntities(sourceId, targetId, OPERATOR);

        assertThat(incoming.getStatus()).isEqualTo(FactStatus.ACTIVE);
        assertThat(incoming.getValidTo()).isNull();
        assertThat(incoming.getEntityId()).isEqualTo(targetId);
    }

    @Test
    void aConcurrentActiveWriteThatLosesTheVersionRaceIsAConflict() {
        UUID entityId = id("person");
        MemoryEntity entity = entity(entityId);
        when(entities.findById(entityId)).thenReturn(Optional.of(entity));
        when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(entityId, FactStatus.ACTIVE))
                .thenReturn(List.of());
        when(entities.save(any(MemoryEntity.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(MemoryEntity.class, entityId));

        assertThatThrownBy(() -> command.addFact(entityId,
                new AddFactCommand("timezone", "UTC", "STRING", Sensitivity.INTERNAL, null), OPERATOR))
                .as("the entity's optimistic version is the guard against two current values")
                .isInstanceOf(MemoryConflictException.class)
                .hasMessageContaining("timezone");

        verify(facts, never()).save(any());
        verify(assertions, never()).save(any());
    }

    @Test
    void anAlreadyMergedSourceCannotBeMergedAgain() {
        UUID sourceId = id("merged");
        MemoryEntity source = entity(sourceId);
        source.setStatus(EntityStatus.MERGED);
        source.setMergedInto(id("survivor"));
        when(entities.findById(sourceId)).thenReturn(Optional.of(source));

        assertThatThrownBy(() -> command.mergeEntities(sourceId, id("survivor"), OPERATOR))
                .isInstanceOf(MemoryConflictException.class);
        verify(entities, never()).save(any());
        verify(assertions, never()).save(any());
    }

    @Test
    void anEntityCannotBeMergedIntoAnAlreadyMergedTarget() {
        UUID sourceId = id("duplicate");
        UUID targetId = id("already-merged");
        when(entities.findById(sourceId)).thenReturn(Optional.of(entity(sourceId)));
        MemoryEntity target = entity(targetId);
        target.setStatus(EntityStatus.MERGED);
        target.setMergedInto(id("survivor"));
        when(entities.findById(targetId)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> command.mergeEntities(sourceId, targetId, OPERATOR))
                .isInstanceOf(MemoryConflictException.class);
        verify(facts, never()).save(any());
        verify(relations, never()).save(any());
    }

    @Test
    void anEntityCannotBeMergedIntoItself() {
        UUID only = id("self");
        assertThatThrownBy(() -> command.mergeEntities(only, only, OPERATOR))
                .isInstanceOf(MemoryValidationException.class);
        verifyNoInteractions(entities, facts, relations, assertions);
    }

    @Test
    void mergeIsOperatorOnly() {
        assertThatThrownBy(() -> command.mergeEntities(id("a"), id("b"),
                new MemoryActor(MemoryActorType.RUN, "run-1")))
                .isInstanceOf(MemoryValidationException.class)
                .hasMessageContaining("operator");
        verifyNoInteractions(entities, facts, relations, assertions);
    }

    // ---------------------------------------------------------------- value guard

    @Test
    void aSecretLookingFactKeyIsRejectedWithoutEchoingIt() {
        assertThatThrownBy(() -> command.addFact(id("person"),
                new AddFactCommand("api_key", "value", "STRING", Sensitivity.INTERNAL, null), OPERATOR))
                .isInstanceOf(MemoryValidationException.class)
                .hasMessageContaining("factKey")
                .hasMessageNotContaining("api_key");
        verifyNoInteractions(entities, facts, relations, assertions);
    }

    @Test
    void aSecretLookingValueIsRejectedWithoutEchoingIt() {
        assertThatThrownBy(() -> command.addFact(id("person"),
                new AddFactCommand("timezone", "sk-live-51H8xQ2eZ", "STRING", Sensitivity.INTERNAL, null), OPERATOR))
                .isInstanceOf(MemoryValidationException.class)
                .hasMessageContaining("valueText")
                .hasMessageNotContaining("sk-live")
                .hasMessageNotContaining("51H8xQ2eZ");
        verifyNoInteractions(entities, facts, relations, assertions);
    }

    @Test
    void aBase64LookingValueIsRejected() {
        String base64 = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo0NTY3ODkw";
        assertThatThrownBy(() -> command.addFact(id("person"),
                new AddFactCommand("timezone", base64, "STRING", Sensitivity.INTERNAL, null), OPERATOR))
                .isInstanceOf(MemoryValidationException.class)
                .hasMessageNotContaining(base64);
    }

    // ---------------------------------------------------------------- entities

    @Test
    void anEntityWithoutACanonicalKeyIsRejected() {
        assertThatThrownBy(() -> command.createEntity(
                new CreateEntityCommand("PERSON", " ", "Operator", Sensitivity.INTERNAL), OPERATOR))
                .isInstanceOf(MemoryValidationException.class)
                .hasMessageContaining("canonicalKey");
        verifyNoInteractions(entities, assertions);
    }

    /**
     * The 2026-11-11 operator decision amends the spec §7 reading that an extraction may never
     * create an entity: it may, but only as PENDING, so Aria can bootstrap an operator profile
     * that the operator then approves. The scenario still pins the part that did not change —
     * an extraction can never mint an ACTIVE entity.
     */
    @Test
    void anExtractionCreatesAPendingEntityAndNeverAnActiveOne() {
        MemoryEntity created = command.createEntity(
                new CreateEntityCommand("PERSON", "operator", "Operator", Sensitivity.INTERNAL), EXTRACTION);

        assertThat(created.getStatus())
                .as("an unapproved extraction entity is review material, never memory")
                .isEqualTo(EntityStatus.PENDING)
                .isNotEqualTo(EntityStatus.ACTIVE);
        assertThat(created.getOrigin()).isEqualTo(MemoryOrigin.EXTRACTION);
        assertThat(captureAssertions(1).get(0).getAfterJson())
                .as("the CREATE assertion records the real lifecycle status")
                .contains("PENDING")
                .doesNotContain("\"status\":\"ACTIVE\"");
    }

    @Test
    void anExtractionActorProposesAPendingEntityInsteadOfBeingRefused() {
        MemoryActor extraction = new MemoryActor(MemoryActorType.EXTRACTION, "run-1");
        MemoryEntity entity = command.createEntity(
                new CreateEntityCommand("PERSON", "person:happy", "Happy", Sensitivity.INTERNAL), extraction);

        assertThat(entity.getStatus()).isEqualTo(EntityStatus.PENDING);
        assertThat(entity.getOrigin()).isEqualTo(MemoryOrigin.EXTRACTION);
    }

    @Test
    void anOperatorStillCreatesAnActiveEntity() {
        MemoryEntity entity = command.createEntity(
                new CreateEntityCommand("PERSON", "person:happy", "Happy", Sensitivity.INTERNAL),
                new MemoryActor(MemoryActorType.OPERATOR, "operator-session"));
        assertThat(entity.getStatus()).isEqualTo(EntityStatus.ACTIVE);
    }

    @Test
    void aRepeatedKeyAttachesToTheExistingPendingEntity() {
        MemoryActor extraction = new MemoryActor(MemoryActorType.EXTRACTION, "run-1");
        MemoryEntity[] stored = new MemoryEntity[1];
        when(entities.findByKindAndCanonicalKey("PERSON", "person:happy"))
                .thenAnswer(invocation -> stored[0] == null ? Optional.empty() : Optional.of(stored[0]));
        when(entities.save(any(MemoryEntity.class))).thenAnswer(invocation -> {
            stored[0] = invocation.getArgument(0);
            return stored[0];
        });

        MemoryEntity first = command.createEntity(
                new CreateEntityCommand("PERSON", "person:happy", "Happy", null), extraction);
        MemoryEntity second = command.createEntity(
                new CreateEntityCommand("PERSON", "person:happy", "Happy", null), extraction);

        assertThat(second.getId()).isEqualTo(first.getId());
        verify(entities, times(1)).save(any(MemoryEntity.class));
        verify(assertions, times(1)).save(any(MemoryAssertion.class));
    }

    /**
     * Minor 1 (T10 fix round 1). {@code UNIQUE (kind, canonical_key)} makes the row the identity
     * of the key, so a new extraction for a key whose proposal was rejected receives that same
     * RETIRED row: no PENDING row, no CREATE assertion, no event, and {@code decideEntity} can
     * never promote it (it refuses a non-PENDING row). A terminal row must not silently swallow
     * a new proposal. Re-proposing resets the identity row to PENDING — never to ACTIVE, because
     * the promotion is still the operator's decision — records the suppressed terminal status on
     * the CREATE assertion and publishes the card event again. The earlier RETIRE/MERGE
     * assertions keep the decided history.
     */
    @Test
    void aNewExtractionProposalForARetiredKeyReProposesItAsPending() {
        UUID runId = UUID.randomUUID();
        MemoryActor extraction = new MemoryActor(MemoryActorType.EXTRACTION, runId.toString());
        MemoryEntity retired = entity(id("person:happy"));
        retired.setStatus(EntityStatus.RETIRED);
        when(entities.findByKindAndCanonicalKey("PERSON", "person:happy")).thenReturn(Optional.of(retired));

        MemoryEntity reproposed = command.createEntity(
                new CreateEntityCommand("PERSON", "person:happy", "Happy", Sensitivity.INTERNAL), extraction);

        assertThat(reproposed.getId()).as("the canonical key is the identity").isEqualTo(retired.getId());
        assertThat(reproposed.getStatus())
                .as("a re-proposal is review material, never a resurrection into ACTIVE")
                .isEqualTo(EntityStatus.PENDING);
        MemoryAssertion created = captureAssertions(1).get(0);
        assertThat(created.getAction()).isEqualTo(MemoryAction.CREATE);
        assertThat(created.getBeforeJson())
                .as("the suppressed terminal decision stays auditable")
                .contains("RETIRED");
        assertThat(created.getAfterJson()).contains("PENDING").doesNotContain("\"status\":\"ACTIVE\"");
        ArgumentCaptor<ApplicationEvent> published = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(events).publishEvent(published.capture());
        assertThat(((MemoryProposalCreatedEvent) published.getValue()).getProposalId()).isEqualTo(retired.getId());
    }

    @Test
    void aNewExtractionProposalForAMergedKeyReProposesItAsPending() {
        UUID runId = UUID.randomUUID();
        MemoryActor extraction = new MemoryActor(MemoryActorType.EXTRACTION, runId.toString());
        UUID survivor = id("survivor");
        MemoryEntity merged = entity(id("person:happy"));
        merged.setStatus(EntityStatus.MERGED);
        merged.setMergedInto(survivor);
        when(entities.findByKindAndCanonicalKey("PERSON", "person:happy")).thenReturn(Optional.of(merged));

        MemoryEntity reproposed = command.createEntity(
                new CreateEntityCommand("PERSON", "person:happy", "Happy", Sensitivity.INTERNAL), extraction);

        assertThat(reproposed.getStatus()).isEqualTo(EntityStatus.PENDING);
        assertThat(reproposed.getMergedInto())
                .as("the key is an open proposal again, so the merge redirect no longer applies")
                .isNull();
        MemoryAssertion created = captureAssertions(1).get(0);
        assertThat(created.getBeforeJson())
                .as("the merge the re-proposal superseded stays auditable")
                .contains("MERGED")
                .contains(survivor.toString());
        ArgumentCaptor<ApplicationEvent> published = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(events).publishEvent(published.capture());
        assertThat(((MemoryProposalCreatedEvent) published.getValue()).getProposalId()).isEqualTo(merged.getId());
    }

    /**
     * N1 (T10 fix round 1 re-review). Lifecycle retire writes no fact row, so a RETIRED entity
     * keeps its {@code status=ACTIVE, valid_to=null} facts. A re-proposal turns the identity row
     * back to PENDING and, before this fix, left them current: approving the new proposal
     * silently promoted a value the card never listed and the operator never decided. The
     * re-proposal must retire the current graph the retired entity left behind, so nothing
     * ACTIVE can survive under the PENDING row unless it is part of the new batch.
     */
    @Test
    void reProposingARetiredKeyRetiresItsSurvivingActiveFacts() {
        UUID runId = UUID.randomUUID();
        MemoryActor extraction = new MemoryActor(MemoryActorType.EXTRACTION, runId.toString());
        MemoryEntity retired = entity(id("person:happy"));
        retired.setStatus(EntityStatus.RETIRED);
        MemoryFact live = currentFact(id("fact:timezone"), retired.getId(), "timezone", "UTC", 3);
        when(entities.findByKindAndCanonicalKey("PERSON", "person:happy")).thenReturn(Optional.of(retired));
        when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(retired.getId(), FactStatus.ACTIVE))
                .thenReturn(List.of(live));
        when(relations.findByFromEntityIdAndStatus(retired.getId(), FactStatus.ACTIVE)).thenReturn(List.of());
        when(relations.findByToEntityIdAndStatus(retired.getId(), FactStatus.ACTIVE)).thenReturn(List.of());

        MemoryEntity reproposed = command.createEntity(
                new CreateEntityCommand("PERSON", "person:happy", "Happy", Sensitivity.INTERNAL), extraction);

        assertThat(reproposed.getStatus()).isEqualTo(EntityStatus.PENDING);
        assertThat(live.getStatus())
                .as("no ACTIVE fact may survive under the re-proposed PENDING row")
                .isEqualTo(FactStatus.RETIRED);
        assertThat(live.getValidTo()).as("the surviving value is closed, not current").isNotNull();
        verify(facts).save(live);
        List<MemoryAssertion> written = captureAssertions(2);
        assertThat(written).filteredOn(assertion -> assertion.getSubjectType() == MemorySubjectType.FACT)
                .singleElement()
                .satisfies(assertion -> {
                    assertThat(assertion.getAction()).isEqualTo(MemoryAction.RETIRE);
                    assertThat(assertion.getSubjectId()).isEqualTo(live.getId());
                    assertThat(assertion.getBeforeJson()).contains("ACTIVE").contains("timezone");
                    assertThat(assertion.getAfterJson()).contains("RETIRED");
                });
        assertThat(written).filteredOn(assertion -> assertion.getSubjectType() == MemorySubjectType.ENTITY)
                .singleElement()
                .satisfies(assertion -> assertThat(assertion.getAction()).isEqualTo(MemoryAction.CREATE));
    }

    /**
     * T29 Item 3 (P3). Lifecycle retire leaves the entity's PENDING facts PENDING, so the next
     * extraction of the same key inherited them: the operator approved the re-proposed entity and
     * the decision promoted a value the current extraction never proposed. The re-proposal must
     * close the stale PENDING rows with the same RETIRE assertion and count them on
     * {@code factsRetired}, so the batch the new proposal creates holds only what it wrote.
     */
    @Test
    void reProposingARetiredKeyRetiresItsStalePendingFactsAndCountsThem() {
        UUID runId = UUID.randomUUID();
        MemoryActor extraction = new MemoryActor(MemoryActorType.EXTRACTION, runId.toString());
        MemoryEntity retired = entity(id("person:happy"));
        retired.setStatus(EntityStatus.RETIRED);
        MemoryFact stale = currentFact(id("fact:stale"), retired.getId(), "preferred_editor", "vim", 1);
        stale.setStatus(FactStatus.PENDING);
        when(entities.findByKindAndCanonicalKey("PERSON", "person:happy")).thenReturn(Optional.of(retired));
        when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(retired.getId(), FactStatus.ACTIVE))
                .thenReturn(List.of());
        when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(retired.getId(), FactStatus.PENDING))
                .thenReturn(List.of(stale));
        when(relations.findByFromEntityIdAndStatus(retired.getId(), FactStatus.ACTIVE)).thenReturn(List.of());
        when(relations.findByToEntityIdAndStatus(retired.getId(), FactStatus.ACTIVE)).thenReturn(List.of());

        command.createEntity(
                new CreateEntityCommand("PERSON", "person:happy", "Happy", Sensitivity.INTERNAL), extraction);

        assertThat(stale.getStatus())
                .as("a stale PENDING row must not survive into the new batch")
                .isEqualTo(FactStatus.RETIRED);
        assertThat(stale.getValidTo()).as("the stale proposal is closed, not current").isNotNull();
        verify(facts).save(stale);
        List<MemoryAssertion> written = captureAssertions(2);
        assertThat(written).filteredOn(assertion -> assertion.getSubjectType() == MemorySubjectType.FACT)
                .singleElement()
                .satisfies(assertion -> {
                    assertThat(assertion.getAction()).isEqualTo(MemoryAction.RETIRE);
                    assertThat(assertion.getSubjectId()).isEqualTo(stale.getId());
                    assertThat(assertion.getBeforeJson()).contains("PENDING").contains("preferred_editor");
                });
        assertThat(written).filteredOn(assertion -> assertion.getSubjectType() == MemorySubjectType.ENTITY)
                .singleElement()
                .satisfies(assertion -> assertThat(assertion.getAfterJson())
                        .as("the closure is recorded on the re-proposal tally")
                        .contains("\"factsRetired\":1"));
    }

    /**
     * T29 Item 4 (P3, bounded). The re-proposal translated only {@code entities.flush()}, but
     * Hibernate flushes the whole persistence context, so a lost FACT version surfaced from that
     * call and was reported as "memory entity … was re-proposed concurrently". The result was
     * already correct (rollback, one governed conflict, no event); the attribution pointed the
     * incident at the wrong row. The fact rows are now flushed through the same governed
     * translation before the entity flush, so the conflict names the fact key.
     */
    @Test
    void aReProposalWhoseFactVersionWasLostNamesTheFactNotTheEntity() {
        UUID runId = UUID.randomUUID();
        MemoryActor extraction = new MemoryActor(MemoryActorType.EXTRACTION, runId.toString());
        MemoryEntity retired = entity(id("person:happy"));
        retired.setStatus(EntityStatus.RETIRED);
        MemoryFact live = currentFact(id("fact:timezone"), retired.getId(), "timezone", "UTC", 3);
        when(entities.findByKindAndCanonicalKey("PERSON", "person:happy")).thenReturn(Optional.of(retired));
        when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(retired.getId(), FactStatus.ACTIVE))
                .thenReturn(List.of(live));
        // The conflict is raised by the ACTIVE row's flush, so the PENDING read never runs here.
        lenient().when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(retired.getId(),
                FactStatus.PENDING)).thenReturn(List.of());
        lenient().when(relations.findByFromEntityIdAndStatus(retired.getId(), FactStatus.ACTIVE)).thenReturn(List.of());
        lenient().when(relations.findByToEntityIdAndStatus(retired.getId(), FactStatus.ACTIVE)).thenReturn(List.of());
        doThrow(new ObjectOptimisticLockingFailureException(MemoryFact.class, live.getId()))
                .when(facts).flush();

        assertThatThrownBy(() -> command.createEntity(
                new CreateEntityCommand("PERSON", "person:happy", "Happy", Sensitivity.INTERNAL), extraction))
                .as("the conflict names the row whose version was lost, not the entity")
                .isInstanceOf(MemoryConflictException.class)
                .hasMessageContaining("timezone")
                .hasMessageNotContaining("was re-proposed concurrently");

        verify(assertions, never()).save(any(MemoryAssertion.class));
        verify(events, never()).publishEvent(any(ApplicationEvent.class));
    }

    /**
     * The same resurrection on the graph: approving the re-proposed entity makes its surviving
     * ACTIVE relations current again (recall expands them from an ACTIVE match), so the edges the
     * retired entity left behind must be closed with it. One edge that is both incoming and
     * outgoing (a self relation) is retired once.
     */
    @Test
    void reProposingARetiredKeyRetiresItsSurvivingActiveRelations() {
        UUID runId = UUID.randomUUID();
        MemoryActor extraction = new MemoryActor(MemoryActorType.EXTRACTION, runId.toString());
        MemoryEntity retired = entity(id("person:happy"));
        retired.setStatus(EntityStatus.RETIRED);
        MemoryRelation live = relation(id("relation:owns"), retired.getId(), "OWNS", id("repo:conductor"));
        when(entities.findByKindAndCanonicalKey("PERSON", "person:happy")).thenReturn(Optional.of(retired));
        when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(retired.getId(), FactStatus.ACTIVE))
                .thenReturn(List.of());
        when(relations.findByFromEntityIdAndStatus(retired.getId(), FactStatus.ACTIVE)).thenReturn(List.of(live));
        when(relations.findByToEntityIdAndStatus(retired.getId(), FactStatus.ACTIVE)).thenReturn(List.of(live));

        command.createEntity(
                new CreateEntityCommand("PERSON", "person:happy", "Happy", Sensitivity.INTERNAL), extraction);

        assertThat(live.getStatus()).isEqualTo(FactStatus.RETIRED);
        assertThat(live.getValidTo()).isNotNull();
        verify(relations, times(1)).save(live);
        assertThat(captureAssertions(2))
                .filteredOn(assertion -> assertion.getSubjectType() == MemorySubjectType.RELATION)
                .singleElement()
                .satisfies(assertion -> {
                    assertThat(assertion.getAction()).isEqualTo(MemoryAction.RETIRE);
                    assertThat(assertion.getSubjectId()).isEqualTo(live.getId());
                });
    }

    /**
     * N4 (T10 fix round 1 re-review). {@code rePropose} saved the identity row without flushing,
     * so two runs proposing the same terminal key both read version N and the loser threw a raw
     * optimistic-lock failure at commit — after it had already published a card event inside the
     * transaction that rolls back. The flush must run inside the method behind the same governed
     * translation as the decision paths, and before the assertion and the event, so the loser
     * reports one conflict and publishes nothing.
     */
    @Test
    void aConcurrentReProposalIsAGovernedConflict() {
        UUID runId = UUID.randomUUID();
        MemoryActor extraction = new MemoryActor(MemoryActorType.EXTRACTION, runId.toString());
        MemoryEntity retired = entity(id("person:happy"));
        retired.setStatus(EntityStatus.RETIRED);
        when(entities.findByKindAndCanonicalKey("PERSON", "person:happy")).thenReturn(Optional.of(retired));
        when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(retired.getId(), FactStatus.ACTIVE))
                .thenReturn(List.of());
        when(relations.findByFromEntityIdAndStatus(retired.getId(), FactStatus.ACTIVE)).thenReturn(List.of());
        when(relations.findByToEntityIdAndStatus(retired.getId(), FactStatus.ACTIVE)).thenReturn(List.of());
        doThrow(new ObjectOptimisticLockingFailureException(MemoryEntity.class, retired.getId()))
                .when(entities).flush();

        assertThatThrownBy(() -> command.createEntity(
                new CreateEntityCommand("PERSON", "person:happy", "Happy", Sensitivity.INTERNAL), extraction))
                .as("a lost re-proposal is the governed conflict, not a raw exception at commit")
                .isInstanceOf(MemoryConflictException.class)
                .hasMessageContaining(retired.getId().toString())
                .hasMessageContaining("re-proposed concurrently");

        verify(assertions, never()).save(any(MemoryAssertion.class));
        verify(events, never()).publishEvent(any(ApplicationEvent.class));
    }

    @Test
    void aLostUniqueRaceReadsTheWinningEntityBack() {
        MemoryEntity winner = entity(id("winner"));
        when(entities.findByKindAndCanonicalKey("PERSON", "person:happy"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(entities.save(any(MemoryEntity.class)))
                .thenThrow(new DataIntegrityViolationException("uq_memory_entity violated"));

        MemoryEntity returned = command.createEntity(
                new CreateEntityCommand("PERSON", "person:happy", "Happy", Sensitivity.INTERNAL), EXTRACTION);

        assertThat(returned.getId())
                .as("the loser of the unique constraint returns the winner instead of a raw 500")
                .isEqualTo(winner.getId());
    }

    @Test
    void aPendingEntityProposalPublishesAProposalCreatedEvent() {
        UUID runId = UUID.randomUUID();
        MemoryActor extraction = new MemoryActor(MemoryActorType.EXTRACTION, runId.toString());

        MemoryEntity entity = command.createEntity(
                new CreateEntityCommand("PERSON", "person:happy", "Happy", Sensitivity.CONFIDENTIAL), extraction);

        ArgumentCaptor<ApplicationEvent> published = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(events).publishEvent(published.capture());
        assertThat(published.getValue()).isInstanceOf(MemoryProposalCreatedEvent.class);
        MemoryProposalCreatedEvent event = (MemoryProposalCreatedEvent) published.getValue();
        assertThat(event.getProposalId()).isEqualTo(entity.getId());
        assertThat(event.getSubjectType()).isEqualTo("ENTITY");
        assertThat(event.getEntityId()).isEqualTo(entity.getId());
        // Task 12's card item carries the canonical key and the display name in the same two
        // fields a fact proposal uses, so the SSE-fed card renders what the REST inbox renders.
        assertThat(event.getFactKey()).isEqualTo("person:happy");
        assertThat(event.getValueText()).isEqualTo("Happy");
        assertThat(event.getSensitivity()).isEqualTo("CONFIDENTIAL");
        assertThat(event.getRunId()).isEqualTo(runId);
    }

    @Test
    void anActiveEntityAndARunlessExtractionPublishNoProposalEvent() {
        command.createEntity(new CreateEntityCommand("PERSON", "operator", "Operator", null), OPERATOR);
        command.createEntity(new CreateEntityCommand("PERSON", "person:happy", "Happy", null),
                new MemoryActor(MemoryActorType.EXTRACTION, null));

        verify(events, never()).publishEvent(any(ApplicationEvent.class));
        verify(events, never()).publishEvent(any(Object.class));
    }

    /**
     * Task 14: the unknown-key producer. A worker call that names a canonical key and no entity
     * creates the PENDING entity and its first PENDING fact as one operation and publishes
     * exactly one ENTITY proposal event. No agent could reach this path before, which is why the
     * proposal card had no input.
     */
    @Test
    void anExtractionUnknownKeyWritesAPendingEntityAndItsFirstPendingFactWithOneEvent() {
        UUID runId = UUID.randomUUID();
        MemoryActor extraction = new MemoryActor(MemoryActorType.EXTRACTION, runId.toString());
        MemoryEntity[] stored = new MemoryEntity[1];
        when(entities.findByKindAndCanonicalKey("PERSON", "person:happy")).thenReturn(Optional.empty());
        when(entities.save(any(MemoryEntity.class))).thenAnswer(invocation -> {
            stored[0] = invocation.getArgument(0);
            return stored[0];
        });
        when(entities.findById(any())).thenAnswer(invocation -> Optional.ofNullable(stored[0]));
        when(facts.findByEntityIdOrderByValidFromDesc(any(UUID.class))).thenReturn(List.of());

        MemoryFact fact = command.addFactByKey("PERSON", "person:happy",
                new AddFactCommand("preferred_editor", "IntelliJ IDEA", "STRING", Sensitivity.INTERNAL, "run-ref"),
                extraction);

        assertThat(stored[0]).as("the unknown key creates its entity row").isNotNull();
        assertThat(stored[0].getStatus()).isEqualTo(EntityStatus.PENDING);
        assertThat(stored[0].getCanonicalKey()).isEqualTo("person:happy");
        assertThat(fact.getEntityId()).isEqualTo(stored[0].getId());
        assertThat(fact.getStatus()).isEqualTo(FactStatus.PENDING);
        assertThat(fact.getOrigin()).isEqualTo(MemoryOrigin.EXTRACTION);
        ArgumentCaptor<ApplicationEvent> published = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(events, times(1)).publishEvent(published.capture());
        MemoryProposalCreatedEvent event = (MemoryProposalCreatedEvent) published.getValue();
        assertThat(event.getSubjectType()).isEqualTo("ENTITY");
        assertThat(event.getFactKey()).isEqualTo("person:happy");
        assertThat(event.getValueText())
                .as("no display name is supplied, so the canonical key is the name the card shows")
                .isEqualTo("person:happy");
        assertThat(event.getRunId()).isEqualTo(runId);
    }

    /** The operator half of the actor split: the same unknown-key shape writes ACTIVE memory. */
    @Test
    void anOperatorUnknownKeyWritesAnActiveEntityAndItsFirstActiveFact() {
        MemoryEntity[] stored = new MemoryEntity[1];
        when(entities.findByKindAndCanonicalKey("PERSON", "person:happy")).thenReturn(Optional.empty());
        when(entities.save(any(MemoryEntity.class))).thenAnswer(invocation -> {
            stored[0] = invocation.getArgument(0);
            return stored[0];
        });
        when(entities.findById(any())).thenAnswer(invocation -> Optional.ofNullable(stored[0]));
        when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(
                any(UUID.class), eq(FactStatus.ACTIVE))).thenReturn(List.of());

        MemoryFact fact = command.addFactByKey("PERSON", "person:happy",
                new AddFactCommand("preferred_editor", "IntelliJ IDEA", "STRING", Sensitivity.INTERNAL, null),
                new MemoryActor(MemoryActorType.OPERATOR, "operator-session"));

        assertThat(stored[0].getStatus()).isEqualTo(EntityStatus.ACTIVE);
        assertThat(fact.getStatus()).isEqualTo(FactStatus.ACTIVE);
        assertThat(fact.getOrigin()).isEqualTo(MemoryOrigin.OPERATOR);
        verify(events, never()).publishEvent(any(ApplicationEvent.class));
    }

    @Test
    void creatingAnEntityWritesAnAssertionAndDefaultsSensitivity() {
        MemoryEntity created = command.createEntity(
                new CreateEntityCommand("PERSON", "operator", "Operator", null), OPERATOR);

        assertThat(created.getId()).isNotNull();
        assertThat(created.getStatus()).isEqualTo(EntityStatus.ACTIVE);
        assertThat(created.getOrigin()).isEqualTo(MemoryOrigin.OPERATOR);
        assertThat(created.getSensitivity()).isEqualTo(Sensitivity.INTERNAL);
        verify(entities).save(created);
        MemoryAssertion assertion = captureAssertions(1).get(0);
        assertThat(assertion.getSubjectType()).isEqualTo(MemorySubjectType.ENTITY);
        assertThat(assertion.getSubjectId()).isEqualTo(created.getId());
        assertThat(assertion.getAction()).isEqualTo(MemoryAction.CREATE);
        assertThat(assertion.getActorType()).isEqualTo(MemoryActorType.OPERATOR);
        assertThat(assertion.getAfterJson()).isNotBlank();
    }

    // ---------------------------------------------------------------- relations

    @Test
    void operatorRelationsAreActiveAndExtractedRelationsArePending() {
        UUID from = id("operator");
        UUID to = id("conductor");
        when(entities.findById(from)).thenReturn(Optional.of(entity(from)));
        when(entities.findById(to)).thenReturn(Optional.of(entity(to)));

        MemoryRelation active = command.addRelation(
                new AddRelationCommand(from, "OWNS", to, Sensitivity.INTERNAL, null), OPERATOR);
        MemoryRelation pending = command.addRelation(
                new AddRelationCommand(from, "WORKS_ON", to, Sensitivity.INTERNAL, "run-1"), EXTRACTION);

        assertThat(active.getStatus()).isEqualTo(FactStatus.ACTIVE);
        assertThat(active.getOrigin()).isEqualTo(MemoryOrigin.OPERATOR);
        assertThat(pending.getStatus()).isEqualTo(FactStatus.PENDING);
        assertThat(pending.getOrigin()).isEqualTo(MemoryOrigin.EXTRACTION);
        verify(relations, times(2)).save(any());
        assertThat(captureAssertions(2)).extracting(MemoryAssertion::getSubjectType)
                .containsOnly(MemorySubjectType.RELATION);
    }

    @Test
    void aRunCannotWriteARelation() {
        assertThatThrownBy(() -> command.addRelation(
                new AddRelationCommand(id("a"), "OWNS", id("b"), Sensitivity.INTERNAL, "run-1"),
                new MemoryActor(MemoryActorType.RUN, "run-1")))
                .isInstanceOf(MemoryValidationException.class);
        verifyNoInteractions(entities, relations, assertions);
    }

    // ---------------------------------------------------------------- transaction

    /**
     * Every mutating method runs in one transaction. T29 Item 8: the list omitted
     * {@code addFactByKey}, the unknown-key producer, so the behavioural pin the previous lane
     * added for its boundary (the rejected-first-fact rollback in
     * {@link MemoryCommandServiceTransactionTest}) had no static counterpart — deleting the
     * annotation kept this test green. The list now covers it, and a probe that removes the
     * annotation reds a test in this file.
     */
    @Test
    void everyMutationIsTransactional() {
        Set<String> mutations = Set.of("createEntity", "addFact", "addFactByKey", "supersedeFact",
                "retireFact", "addRelation", "mergeEntities");
        assertThat(MemoryCommandService.class.getDeclaredMethods())
                .filteredOn(method -> mutations.contains(method.getName()))
                .hasSize(mutations.size())
                .allSatisfy(method -> assertThat(method.isAnnotationPresent(Transactional.class))
                        .as("%s must run in one transaction", method.getName())
                        .isTrue());
    }

    // ---------------------------------------------------------------- fixtures

    private UUID id(String label) {
        return UUID.nameUUIDFromBytes(label.getBytes(StandardCharsets.UTF_8));
    }

    private MemoryEntity entity(UUID id) {
        return MemoryEntity.builder()
                .id(id)
                .kind("PERSON")
                .canonicalKey("operator")
                .displayName("Operator")
                .sensitivity(Sensitivity.INTERNAL)
                .status(EntityStatus.ACTIVE)
                .origin(MemoryOrigin.OPERATOR)
                .build();
    }

    private MemoryFact currentFact(UUID id, UUID entityId, String key, String value, long version) {
        return MemoryFact.builder()
                .id(id)
                .entityId(entityId)
                .factKey(key)
                .valueText(value)
                .valueType("STRING")
                .status(FactStatus.ACTIVE)
                .origin(MemoryOrigin.OPERATOR)
                .sensitivity(Sensitivity.INTERNAL)
                .validFrom(VALID_FROM)
                .version(version)
                .build();
    }

    private MemoryRelation relation(UUID id, UUID from, String predicate, UUID to) {
        return MemoryRelation.builder()
                .id(id)
                .fromEntityId(from)
                .predicate(predicate)
                .toEntityId(to)
                .status(FactStatus.ACTIVE)
                .origin(MemoryOrigin.OPERATOR)
                .sensitivity(Sensitivity.INTERNAL)
                .validFrom(VALID_FROM)
                .build();
    }

    private MemoryKnowledgeLink link(UUID id, UUID entityId, UUID knowledgeItemId) {
        return MemoryKnowledgeLink.builder()
                .id(id)
                .entityId(entityId)
                .knowledgeItemId(knowledgeItemId)
                .status(MemoryLinkStatus.ACTIVE)
                .createdAt(VALID_FROM)
                .build();
    }

    private List<MemoryAssertion> captureAssertions(int count) {
        ArgumentCaptor<MemoryAssertion> captor = ArgumentCaptor.forClass(MemoryAssertion.class);
        verify(assertions, times(count)).save(captor.capture());
        return captor.getAllValues();
    }
}
