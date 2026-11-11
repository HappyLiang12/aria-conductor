package io.aria.conductor.memory.service;

import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.memory.dto.MemoryActor;
import io.aria.conductor.memory.dto.MemoryProposalView;
import io.aria.conductor.memory.dto.ProposalDecision;
import io.aria.conductor.memory.model.EntityStatus;
import io.aria.conductor.memory.model.FactStatus;
import io.aria.conductor.memory.model.MemoryAction;
import io.aria.conductor.memory.model.MemoryActorType;
import io.aria.conductor.memory.model.MemoryAssertion;
import io.aria.conductor.memory.model.MemoryEntity;
import io.aria.conductor.memory.model.MemoryFact;
import io.aria.conductor.memory.model.MemoryOrigin;
import io.aria.conductor.memory.model.MemoryRelation;
import io.aria.conductor.memory.model.MemorySubjectType;
import io.aria.conductor.memory.repository.MemoryAssertionRepository;
import io.aria.conductor.memory.repository.MemoryEntityRepository;
import io.aria.conductor.memory.repository.MemoryFactRepository;
import io.aria.conductor.memory.repository.MemoryRelationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Proposal review (spec §7, §9): the review inbox lists PENDING subjects, the decision is
 * operator-only, approval promotes the subject and retires any conflicting ACTIVE value,
 * rejection can never leave the subject ACTIVE, and repeating the same decision is a
 * no-op that writes no second assertion.
 */
@ExtendWith(MockitoExtension.class)
class MemoryProposalServiceTest {

    private static final MemoryActor OPERATOR = new MemoryActor(MemoryActorType.OPERATOR, "local");
    private static final MemoryActor WORKER_RUN = new MemoryActor(MemoryActorType.RUN, "run-1");
    private static final MemoryActor WORKER_EXTRACTION = new MemoryActor(MemoryActorType.EXTRACTION, "extract-1");
    private static final Instant OLD = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    MemoryFactRepository facts;

    @Mock
    MemoryRelationRepository relations;

    @Mock
    MemoryAssertionRepository assertions;

    @Mock
    MemoryEntityRepository entities;

    MemoryProposalService proposals;

    @BeforeEach
    void setUp() {
        proposals = new MemoryProposalService(facts, relations, entities, assertions,
                new MemoryCurrentValueSlot(entities));
    }

    // ---------------------------------------------------------------- entity proposals

    /**
     * The card test in Task 12 renders an ENTITY item with the canonical key in {@code factKey}
     * and the display name in {@code valueText}, so the view must carry both: the proposal's own
     * id is what the decision endpoint is addressed to. The read is the OPERATOR's, which is the
     * contract T11 C1 preserves: only a non-operator read omits a PENDING entity's identity.
     */
    @Test
    void listProposalsIncludesPendingEntitiesWithTheirKeyAndDisplayName() {
        MemoryEntity proposal = pendingEntity(UUID.randomUUID());
        when(entities.findByStatusOrderByUpdatedAtDesc(eq(EntityStatus.PENDING), any(Pageable.class)))
                .thenReturn(List.of(proposal));

        List<MemoryProposalView> inbox = proposals.listProposals(null, OPERATOR);

        assertThat(inbox).singleElement().satisfies(view -> {
            assertThat(view.subjectType()).isEqualTo("ENTITY");
            assertThat(view.id()).isEqualTo(proposal.getId().toString());
            assertThat(view.entityId()).isEqualTo(proposal.getId().toString());
            assertThat(view.factKey()).as("the canonical key is the display handle").isEqualTo("person:happy");
            assertThat(view.valueText()).as("the display name is what the operator reads").isEqualTo("Happy");
            assertThat(view.status()).isEqualTo("PENDING");
            assertThat(view.origin()).isEqualTo("EXTRACTION");
        });
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(entities).findByStatusOrderByUpdatedAtDesc(eq(EntityStatus.PENDING), page.capture());
        assertThat(page.getValue().getPageSize())
                .as("the inbox query itself is bounded")
                .isEqualTo(MemoryProposalService.INBOX_LIMIT);
    }

    /**
     * T11 C1 (moved into this lane). {@code list_memory_proposals} is an auto-approved worker
     * read, and the inbox returned PENDING ENTITY rows to it — the canonical key and display name
     * of exactly the rows {@code list_memory_entities(status=PENDING)} refuses. The actor now
     * travels with the read: a worker still sees the PENDING FACT proposals (it must see its own
     * work), but the identity of a PENDING entity is review material for the operator only.
     * The pinned operator contract is
     * {@link #listProposalsIncludesPendingEntitiesWithTheirKeyAndDisplayName}.
     */
    @Test
    void aWorkerViewOfTheProposalsOmitsAPendingEntityIdentityWhileAnOperatorSeesIt() {
        UUID entityId = UUID.randomUUID();
        MemoryEntity entityProposal = pendingEntity(entityId);
        MemoryFact ownFact = fact(UUID.randomUUID(), "timezone", FactStatus.PENDING, OLD.plusSeconds(10));
        when(facts.findByStatusOrderByCreatedAtDesc(eq(FactStatus.PENDING), any(Pageable.class)))
                .thenReturn(List.of(ownFact));
        when(relations.findByStatusOrderByCreatedAtDesc(eq(FactStatus.PENDING), any(Pageable.class)))
                .thenReturn(List.of());
        when(entities.findByStatusOrderByUpdatedAtDesc(eq(EntityStatus.PENDING), any(Pageable.class)))
                .thenReturn(List.of(entityProposal));

        List<MemoryProposalView> workerView = proposals.listProposals(FactStatus.PENDING, WORKER_EXTRACTION);
        List<MemoryProposalView> operatorView = proposals.listProposals(FactStatus.PENDING, OPERATOR);

        assertThat(workerView).extracting(MemoryProposalView::subjectType)
                .as("a worker still sees its own PENDING fact proposal")
                .containsExactly("FACT");
        assertThat(workerView).extracting(MemoryProposalView::factKey)
                .as("but not the canonical key of a PENDING entity")
                .containsExactly("timezone");
        assertThat(operatorView).extracting(MemoryProposalView::subjectType)
                .as("the operator card keeps the entity proposal")
                .containsExactly("FACT", "ENTITY");
        assertThat(operatorView).extracting(MemoryProposalView::factKey)
                .containsExactly("timezone", "person:happy");
        assertThat(operatorView).extracting(MemoryProposalView::valueText)
                .containsExactly("value", "Happy");
    }

    /**
     * M1 (UI surface review). The ENTITY card filtered ONE 50-row page of the merged global inbox
     * client-side, so with more than 50 PENDING facts across entities an entity's own batch fell
     * off the page and the card said "adding the entity alone" while {@code decideEntity} promoted
     * all of them. The per-entity read is a query filter, never a page of the global inbox, and it
     * returns the complete batch — the same rows the decision promotes.
     */
    @Test
    void theEntityFilterReturnsOneEntitysWholeBatchInsteadOfAGlobalInboxPage() {
        UUID happy = UUID.randomUUID();
        List<MemoryFact> otherEntities = new ArrayList<>();
        for (int i = 0; i < MemoryProposalService.INBOX_LIMIT; i++) {
            otherEntities.add(fact(UUID.randomUUID(), "key-" + i, FactStatus.PENDING, OLD.plusSeconds(i)));
        }
        MemoryFact timezone = fact(happy, "timezone", FactStatus.PENDING, OLD.plusSeconds(3));
        MemoryFact language = fact(happy, "language", FactStatus.PENDING, OLD.plusSeconds(2));
        MemoryFact theme = fact(happy, "theme", FactStatus.PENDING, OLD.plusSeconds(1));
        when(facts.findByStatusOrderByCreatedAtDesc(eq(FactStatus.PENDING), any(Pageable.class)))
                .thenReturn(otherEntities);
        when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(happy, FactStatus.PENDING))
                .thenReturn(List.of(timezone, language, theme));
        when(entities.findById(happy)).thenReturn(Optional.of(pendingEntity(happy)));

        List<MemoryProposalView> globalPage = proposals.listProposals(FactStatus.PENDING, OPERATOR);

        assertThat(globalPage).as("the bug's premise: the bounded inbox page is full")
                .hasSize(MemoryProposalService.INBOX_LIMIT);
        assertThat(globalPage).extracting(MemoryProposalView::factKey)
                .as("and none of the entity's facts are on it")
                .doesNotContain("timezone", "language", "theme");

        clearInvocations(facts, relations, entities);
        List<MemoryProposalView> batch = proposals.listProposalsForEntity(FactStatus.PENDING, happy, OPERATOR);

        assertThat(batch).extracting(MemoryProposalView::factKey)
                .as("the filter read returns every fact of the entity and its own proposal")
                .containsExactly("timezone", "language", "theme", "person:happy");
        assertThat(batch).extracting(MemoryProposalView::id).contains(timezone.getId().toString());
        assertThat(batch).extracting(MemoryProposalView::createdAt).isSortedAccordingTo(Comparator.reverseOrder());
        verify(facts).findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(happy, FactStatus.PENDING);
        verify(facts, never()).findByStatusOrderByCreatedAtDesc(any(), any(Pageable.class));
        verify(relations, never()).findByStatusOrderByCreatedAtDesc(any(), any(Pageable.class));
    }

    /**
     * The entity filter composes with the status it is asked for and keeps the actor rule of the
     * inbox: the PENDING entity row is review material, its own PENDING facts are not.
     */
    @Test
    void theEntityFilterComposesWithTheStatusAndTheActor() {
        UUID happy = UUID.randomUUID();
        MemoryFact active = fact(happy, "timezone", FactStatus.ACTIVE, OLD);
        MemoryFact pending = fact(happy, "language", FactStatus.PENDING, OLD.plusSeconds(5));
        when(facts.findByEntityIdAndStatusOrderByCreatedAtDesc(happy, FactStatus.ACTIVE))
                .thenReturn(List.of(active));
        when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(happy, FactStatus.PENDING))
                .thenReturn(List.of(pending));
        when(entities.findById(happy)).thenReturn(Optional.of(entity(happy)));

        List<MemoryProposalView> activeBatch =
                proposals.listProposalsForEntity(FactStatus.ACTIVE, happy, OPERATOR);

        assertThat(activeBatch).extracting(MemoryProposalView::subjectType)
                .containsExactly("FACT", "ENTITY");
        assertThat(activeBatch).extracting(MemoryProposalView::status).containsOnly("ACTIVE");

        when(entities.findById(happy)).thenReturn(Optional.of(pendingEntity(happy)));
        List<MemoryProposalView> workerView =
                proposals.listProposalsForEntity(FactStatus.PENDING, happy, WORKER_EXTRACTION);
        List<MemoryProposalView> operatorView =
                proposals.listProposalsForEntity(FactStatus.PENDING, happy, OPERATOR);

        assertThat(workerView).extracting(MemoryProposalView::subjectType)
                .as("the worker sees the batch it proposed, not the PENDING entity's identity")
                .containsExactly("FACT");
        assertThat(operatorView).extracting(MemoryProposalView::subjectType)
                .containsExactly("FACT", "ENTITY");
    }

    /**
     * T29 Item 1 (P1). The card renders the {@code entityId} filtered read and the operator
     * decides the entity, so the two sets have to be the SAME set: {@code decideEntity} promotes
     * and retires the entity's PENDING FACTS only (spec :116-117, :323-324 — "a proposal batch is
     * the entity's PENDING facts"; the relation graph is out of scope, spec :138, :453). The
     * filtered read used to add the entity's PENDING relations in both directions, so approving
     * the entity left a relation PENDING and approving that relation then survived under a RETIRED
     * entity. Both halves are driven in this one test: the read's fact ids must equal the ids the
     * decision promotes, a PENDING relation must not be in the batch, and it must stay undecided.
     * <p>
     * T31 Item 2: both halves now go through the same query method ({@code status} AND
     * {@code valid_to IS NULL}) as well as the same status, which is why one stub serves both. The
     * predicate itself — a closed PENDING row is not batch material — is pinned against real
     * persistence in
     * {@link MemoryCommandServiceTransactionTest#theEntityBatchReadIsExactlyTheSetTheEntityDecisionDecides}.
     */
    @Test
    void theEntityBatchIsExactlyTheSetTheEntityDecisionPromotes() {
        UUID happy = UUID.randomUUID();
        MemoryEntity entity = pendingEntity(happy);
        MemoryFact timezone = fact(happy, "timezone", FactStatus.PENDING, OLD.plusSeconds(2));
        MemoryFact language = fact(happy, "language", FactStatus.PENDING, OLD.plusSeconds(1));
        MemoryRelation pendingRelation = relation(FactStatus.PENDING);
        when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(happy, FactStatus.PENDING))
                .thenReturn(List.of(timezone, language));
        when(entities.findById(happy)).thenReturn(Optional.of(entity));
        // Stubbed leniently: after the fix the read never looks at relations, and the point of the
        // stub is that a PENDING relation of the entity exists and still must not appear.
        lenient().when(relations.findByFromEntityIdAndStatus(happy, FactStatus.PENDING))
                .thenReturn(List.of(pendingRelation));
        lenient().when(relations.findByToEntityIdAndStatus(happy, FactStatus.PENDING))
                .thenReturn(List.of(pendingRelation));

        List<MemoryProposalView> batch = proposals.listProposalsForEntity(FactStatus.PENDING, happy, OPERATOR);

        List<String> shownFactIds = new ArrayList<>();
        for (MemoryProposalView view : batch) {
            if ("FACT".equals(view.subjectType())) {
                shownFactIds.add(view.id());
            }
        }
        assertThat(shownFactIds)
                .as("the card shows the entity's PENDING facts")
                .containsExactlyInAnyOrder(timezone.getId().toString(), language.getId().toString());
        assertThat(batch).extracting(MemoryProposalView::subjectType)
                .as("facts plus the entity row, never a relation")
                .containsExactlyInAnyOrder("FACT", "FACT", "ENTITY");
        assertThat(batch).extracting(MemoryProposalView::id)
                .doesNotContain(pendingRelation.getId().toString());
        verify(relations, never()).findByFromEntityIdAndStatus(any(), any());
        verify(relations, never()).findByToEntityIdAndStatus(any(), any());

        when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(happy, FactStatus.PENDING))
                .thenReturn(List.of(timezone, language));
        when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(happy, FactStatus.ACTIVE))
                .thenReturn(List.of());
        proposals.decideProposal(happy, ProposalDecision.APPROVE, OPERATOR);

        List<String> promotedFactIds = new ArrayList<>();
        for (MemoryFact fact : List.of(timezone, language)) {
            if (fact.getStatus() == FactStatus.ACTIVE) {
                promotedFactIds.add(fact.getId().toString());
            }
        }
        assertThat(promotedFactIds)
                .as("the batch the card showed is exactly the batch the decision covered")
                .containsExactlyInAnyOrderElementsOf(shownFactIds);
        assertThat(pendingRelation.getStatus())
                .as("a relation is out of scope for the entity decision")
                .isEqualTo(FactStatus.PENDING);
    }

    @Test
    void theEntityFilterRequiresAnEntityId() {
        assertThatThrownBy(() -> proposals.listProposalsForEntity(FactStatus.PENDING, null, OPERATOR))
                .isInstanceOf(MemoryValidationException.class)
                .hasMessageContaining("entityId");
        verifyNoInteractions(facts, relations, entities);
    }

    @Test
    void approvingAnEntityProposalPromotesEveryPendingFactOfItsBatch() {
        UUID entityId = UUID.randomUUID();
        MemoryEntity entity = pendingEntity(entityId);
        MemoryFact timezone = fact(entityId, "timezone", FactStatus.PENDING, OLD);
        MemoryFact language = fact(entityId, "language", FactStatus.PENDING, OLD);
        when(entities.findById(entityId)).thenReturn(Optional.of(entity));
        when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(entityId, FactStatus.PENDING))
                .thenReturn(List.of(timezone, language));
        when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(entityId, FactStatus.ACTIVE))
                .thenReturn(List.of());

        MemoryProposalView decided = proposals.decideProposal(entityId, ProposalDecision.APPROVE, OPERATOR);

        assertThat(decided.subjectType()).isEqualTo("ENTITY");
        assertThat(decided.status()).isEqualTo("ACTIVE");
        assertThat(entity.getStatus()).isEqualTo(EntityStatus.ACTIVE);
        assertThat(timezone.getStatus()).isEqualTo(FactStatus.ACTIVE);
        assertThat(language.getStatus()).isEqualTo(FactStatus.ACTIVE);

        ArgumentCaptor<MemoryAssertion> written = ArgumentCaptor.forClass(MemoryAssertion.class);
        verify(assertions, times(3)).save(written.capture());
        assertThat(written.getAllValues()).filteredOn(assertion ->
                        assertion.getSubjectType() == MemorySubjectType.ENTITY)
                .singleElement()
                .satisfies(assertion -> {
                    assertThat(assertion.getAction()).isEqualTo(MemoryAction.UPDATE);
                    assertThat(assertion.getSubjectId()).isEqualTo(entityId);
                    assertThat(assertion.getBeforeJson()).contains("PENDING");
                    assertThat(assertion.getAfterJson()).contains("ACTIVE");
                });
        assertThat(written.getAllValues()).filteredOn(assertion ->
                        assertion.getSubjectType() == MemorySubjectType.FACT)
                .extracting(MemoryAssertion::getSubjectId)
                .containsExactlyInAnyOrder(timezone.getId(), language.getId());
    }

    @Test
    void approvingAnEntityWithNoPendingFactsClaimsNoFactSlot() {
        UUID entityId = UUID.randomUUID();
        MemoryEntity entity = pendingEntity(entityId);
        when(entities.findById(entityId)).thenReturn(Optional.of(entity));
        when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(entityId, FactStatus.PENDING))
                .thenReturn(List.of());

        proposals.decideProposal(entityId, ProposalDecision.APPROVE, OPERATOR);

        assertThat(entity.getStatus()).isEqualTo(EntityStatus.ACTIVE);
        verify(entities, times(1)).save(entity);
        verify(assertions, times(1)).save(any(MemoryAssertion.class));
    }

    @Test
    void rejectingAnEntityProposalRetiresTheEntityAndItsPendingFacts() {
        UUID entityId = UUID.randomUUID();
        MemoryEntity entity = pendingEntity(entityId);
        MemoryFact timezone = fact(entityId, "timezone", FactStatus.PENDING, OLD);
        MemoryFact language = fact(entityId, "language", FactStatus.PENDING, OLD);
        when(entities.findById(entityId)).thenReturn(Optional.of(entity));
        when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(entityId, FactStatus.PENDING))
                .thenReturn(List.of(timezone, language));

        MemoryProposalView decided = proposals.decideProposal(entityId, ProposalDecision.REJECT, OPERATOR);

        assertThat(decided.status()).isEqualTo("RETIRED");
        assertThat(entity.getStatus()).isEqualTo(EntityStatus.RETIRED);
        assertThat(timezone.getStatus()).isEqualTo(FactStatus.RETIRED);
        assertThat(timezone.getValidTo()).as("a rejected proposal is closed, not current").isNotNull();
        assertThat(language.getStatus()).isEqualTo(FactStatus.RETIRED);

        ArgumentCaptor<MemoryAssertion> written = ArgumentCaptor.forClass(MemoryAssertion.class);
        verify(assertions, times(3)).save(written.capture());
        assertThat(written.getAllValues()).extracting(MemoryAssertion::getAction)
                .containsOnly(MemoryAction.RETIRE);
        assertThat(written.getAllValues()).filteredOn(assertion ->
                        assertion.getSubjectType() == MemorySubjectType.ENTITY)
                .singleElement()
                .satisfies(assertion -> assertThat(assertion.getSubjectId()).isEqualTo(entityId));
    }

    @Test
    void aRejectedEntityProposalCanNeverBeApprovedAfterwards() {
        UUID entityId = UUID.randomUUID();
        MemoryEntity entity = pendingEntity(entityId);
        when(entities.findById(entityId)).thenReturn(Optional.of(entity));
        when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(entityId, FactStatus.PENDING))
                .thenReturn(List.of());

        proposals.decideProposal(entityId, ProposalDecision.REJECT, OPERATOR);

        assertThatThrownBy(() -> proposals.decideProposal(entityId, ProposalDecision.APPROVE, OPERATOR))
                .isInstanceOf(MemoryConflictException.class)
                .hasMessageContaining("RETIRED");
    }

    @Test
    void decidingTheSameEntityOutcomeTwiceIsIdempotent() {
        UUID entityId = UUID.randomUUID();
        MemoryEntity entity = pendingEntity(entityId);
        when(entities.findById(entityId)).thenReturn(Optional.of(entity));
        when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(entityId, FactStatus.PENDING))
                .thenReturn(List.of());

        proposals.decideProposal(entityId, ProposalDecision.APPROVE, OPERATOR);
        MemoryProposalView again = proposals.decideProposal(entityId, ProposalDecision.APPROVE, OPERATOR);

        assertThat(again.status()).isEqualTo("ACTIVE");
        assertThat(entity.getStatus()).isEqualTo(EntityStatus.ACTIVE);
        verify(assertions, times(1)).save(any(MemoryAssertion.class));
        verify(entities, times(1)).save(entity);
    }

    @Test
    void listProposalsReturnsPendingFactsAndRelationsOnly() {
        MemoryFact pendingFact = fact(UUID.randomUUID(), "editor", FactStatus.PENDING, OLD);
        MemoryFact activeFact = fact(pendingFact.getEntityId(), "timezone", FactStatus.ACTIVE, OLD);
        MemoryRelation pendingRelation = relation(FactStatus.PENDING);
        MemoryRelation activeRelation = relation(FactStatus.ACTIVE);
        when(facts.findByStatusOrderByCreatedAtDesc(eq(FactStatus.PENDING), any(Pageable.class)))
                .thenReturn(List.of(pendingFact));
        when(relations.findByStatusOrderByCreatedAtDesc(eq(FactStatus.PENDING), any(Pageable.class)))
                .thenReturn(List.of(pendingRelation));

        List<MemoryProposalView> pending = proposals.listProposals(FactStatus.PENDING);

        assertThat(pending).extracting(MemoryProposalView::subjectType)
                .containsExactly("FACT", "RELATION");
        assertThat(pending.get(0).factKey()).isEqualTo("editor");
        assertThat(pending.get(0).status()).isEqualTo("PENDING");
        assertThat(pending.get(0).origin()).isEqualTo("EXTRACTION");
        assertThat(pending.get(1).predicate()).isEqualTo("OWNS");
        assertThat(pending.get(1).status()).isEqualTo("PENDING");
    }

    /**
     * The inbox is bounded and newest first, so reviewing proposals cannot load the whole
     * fact and relation history and cannot be dominated by the oldest rows.
     */
    @Test
    void theInboxIsBoundedAndNewestFirst() {
        List<MemoryFact> pendingFacts = new ArrayList<>();
        List<MemoryRelation> pendingRelations = new ArrayList<>();
        for (int i = 0; i < MemoryProposalService.INBOX_LIMIT; i++) {
            pendingFacts.add(fact(UUID.randomUUID(), "key-" + i, FactStatus.PENDING,
                    OLD.plusSeconds(i)));
            pendingRelations.add(relation(FactStatus.PENDING, OLD.plusSeconds(i).plusMillis(500)));
        }
        pendingFacts.add(fact(UUID.randomUUID(), "key-oldest", FactStatus.PENDING, OLD.minusSeconds(60)));
        when(facts.findByStatusOrderByCreatedAtDesc(eq(FactStatus.PENDING), any(Pageable.class)))
                .thenReturn(pendingFacts);
        when(relations.findByStatusOrderByCreatedAtDesc(eq(FactStatus.PENDING), any(Pageable.class)))
                .thenReturn(pendingRelations);

        List<MemoryProposalView> inbox = proposals.listProposals(null);

        assertThat(inbox).as("the inbox page is capped").hasSize(MemoryProposalService.INBOX_LIMIT);
        assertThat(inbox).extracting(MemoryProposalView::createdAt)
                .as("newest first")
                .isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(inbox).extracting(MemoryProposalView::factKey).doesNotContain("key-oldest");

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(facts).findByStatusOrderByCreatedAtDesc(eq(FactStatus.PENDING), page.capture());
        assertThat(page.getValue().getPageSize())
                .as("the query itself is bounded")
                .isEqualTo(MemoryProposalService.INBOX_LIMIT);
        assertThat(page.getValue().getOffset()).isZero();
        verify(facts, never()).findAll();
        verify(relations, never()).findAll();
    }

    @Test
    void approvingAProposalRetiresTheConflictingActiveValueAndPromotesTheProposal() {
        UUID entityId = UUID.randomUUID();
        MemoryFact active = fact(entityId, "timezone", FactStatus.ACTIVE, OLD);
        MemoryFact proposed = fact(entityId, "timezone", FactStatus.PENDING, OLD);
        when(facts.findById(proposed.getId())).thenReturn(Optional.of(proposed));
        when(entities.findById(entityId)).thenReturn(Optional.of(entity(entityId)));
        when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(entityId, FactStatus.ACTIVE))
                .thenReturn(List.of(active));

        MemoryProposalView decided = proposals.decideProposal(proposed.getId(), ProposalDecision.APPROVE, OPERATOR);

        assertThat(decided.status()).isEqualTo("ACTIVE");
        assertThat(decided.subjectType()).isEqualTo("FACT");
        assertThat(decided.entityId()).isEqualTo(entityId.toString());
        assertThat(proposed.getStatus()).isEqualTo(FactStatus.ACTIVE);
        assertThat(proposed.getValidFrom()).isAfter(OLD);
        assertThat(active.getStatus()).isEqualTo(FactStatus.RETIRED);
        assertThat(active.getValidTo()).isNotNull();
        verify(facts).save(active);
        verify(facts).save(proposed);
        ArgumentCaptor<MemoryAssertion> written = ArgumentCaptor.forClass(MemoryAssertion.class);
        verify(assertions, times(2)).save(written.capture());
        assertThat(written.getAllValues()).extracting(MemoryAssertion::getAction)
                .containsExactly(MemoryAction.SUPERSEDE, MemoryAction.UPDATE);
        assertThat(written.getAllValues()).allSatisfy(assertion -> {
            assertThat(assertion.getActorType()).isEqualTo(MemoryActorType.OPERATOR);
            assertThat(assertion.getSubjectType()).isEqualTo(MemorySubjectType.FACT);
        });
    }

    /**
     * Positive control for the PENDING-parent refusal below: a fact proposal whose parent is
     * ACTIVE is still promoted by one fact decision, so the M1 guard refuses the invisible-parent
     * case only and does not turn the fact inbox read-only.
     */
    @Test
    void approvingWithoutAConflictWritesOneAssertion() {
        MemoryFact proposed = fact(UUID.randomUUID(), "timezone", FactStatus.PENDING, OLD);
        when(facts.findById(proposed.getId())).thenReturn(Optional.of(proposed));
        when(entities.findById(proposed.getEntityId())).thenReturn(Optional.of(entity(proposed.getEntityId())));
        when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(proposed.getEntityId(),
                FactStatus.ACTIVE)).thenReturn(List.of());

        proposals.decideProposal(proposed.getId(), ProposalDecision.APPROVE, OPERATOR);

        assertThat(proposed.getStatus()).isEqualTo(FactStatus.ACTIVE);
        ArgumentCaptor<MemoryAssertion> written = ArgumentCaptor.forClass(MemoryAssertion.class);
        verify(assertions).save(written.capture());
        assertThat(written.getValue().getAction()).isEqualTo(MemoryAction.UPDATE);
        assertThat(written.getValue().getBeforeJson()).contains("PENDING");
        assertThat(written.getValue().getAfterJson()).contains("ACTIVE");
    }

    /**
     * M1 (T10 fix round 1). The inbox lists a PENDING entity's facts one by one, so an operator
     * can click a fact card that belongs to the batch the entity card owns. Approving it would
     * make the fact ACTIVE under an entity no read path can see (and a later entity rejection
     * retires only PENDING rows, orphaning it under a RETIRED parent). The refusal has to be
     * actionable: it names the entity decision instead of failing bare.
     */
    @Test
    void approvingAFactUnderAPendingEntityIsRefusedInFavourOfTheEntityDecision() {
        UUID entityId = UUID.randomUUID();
        MemoryFact proposed = fact(entityId, "timezone", FactStatus.PENDING, OLD);
        when(facts.findById(proposed.getId())).thenReturn(Optional.of(proposed));
        when(entities.findById(entityId)).thenReturn(Optional.of(pendingEntity(entityId)));

        assertThatThrownBy(() -> proposals.decideProposal(proposed.getId(), ProposalDecision.APPROVE, OPERATOR))
                .as("the entity card is the decision the operator has to make")
                .isInstanceOf(MemoryConflictException.class)
                .hasMessageContaining(entityId.toString())
                .hasMessageContaining("PENDING")
                .hasMessageContaining("entity proposal");

        assertThat(proposed.getStatus()).as("a refused approval promotes nothing").isEqualTo(FactStatus.PENDING);
        assertThat(proposed.getValidTo()).isNull();
        verify(facts, never()).save(any());
        verify(assertions, never()).save(any());
    }

    /**
     * N2 (T10 fix round 1 re-review). The M1 guard compared only against PENDING, so a PENDING
     * fact whose parent was retired between the proposal and the decision could still be approved:
     * the fact became ACTIVE under a RETIRED parent, invisible to recall, the prompt, the default
     * list and the detail read — the exact harm M1 refused for a PENDING parent. The refusal must
     * cover every non-ACTIVE parent and name the case it found. The positive control is
     * {@link #approvingWithoutAConflictWritesOneAssertion}: an ACTIVE parent still promotes.
     */
    @Test
    void approvingAFactUnderANonActiveEntityIsRefusedNamingTheDeadParent() {
        for (EntityStatus parentStatus : new EntityStatus[]{EntityStatus.RETIRED, EntityStatus.MERGED}) {
            UUID entityId = UUID.randomUUID();
            MemoryFact proposed = fact(entityId, "timezone", FactStatus.PENDING, OLD);
            MemoryEntity parent = pendingEntity(entityId);
            parent.setStatus(parentStatus);
            when(facts.findById(proposed.getId())).thenReturn(Optional.of(proposed));
            when(entities.findById(entityId)).thenReturn(Optional.of(parent));

            assertThatThrownBy(() -> proposals.decideProposal(proposed.getId(), ProposalDecision.APPROVE,
                    OPERATOR))
                    .as("a fact may only become current under an ACTIVE parent")
                    .isInstanceOf(MemoryConflictException.class)
                    .hasMessageContaining(entityId.toString())
                    .hasMessageContaining(parentStatus.name())
                    .hasMessageContaining("no longer active");

            assertThat(proposed.getStatus()).as("a refused approval promotes nothing")
                    .isEqualTo(FactStatus.PENDING);
            assertThat(proposed.getValidTo()).isNull();
        }
        verify(facts, never()).save(any());
        verify(assertions, never()).save(any());
    }

    /**
     * N3 (T10 fix round 1 re-review). The reject branch saved every batch fact without flushing,
     * so the first flush was the entity one: a lost FACT version surfaced as "memory entity …
     * changed", pointing the incident at the wrong row. The fact flush now runs per fact inside
     * the same translation, so the conflict names the row that actually lost the race.
     */
    @Test
    void aRejectedBatchWhoseFactVersionWasChangedUnderneathNamesTheFactNotTheEntity() {
        UUID entityId = UUID.randomUUID();
        MemoryEntity entity = pendingEntity(entityId);
        MemoryFact timezone = fact(entityId, "timezone", FactStatus.PENDING, OLD);
        when(entities.findById(entityId)).thenReturn(Optional.of(entity));
        when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(entityId, FactStatus.PENDING))
                .thenReturn(List.of(timezone));
        doThrow(new ObjectOptimisticLockingFailureException(MemoryFact.class, timezone.getId()))
                .when(facts).flush();

        assertThatThrownBy(() -> proposals.decideProposal(entityId, ProposalDecision.REJECT, OPERATOR))
                .as("the conflict names the row whose version was lost")
                .isInstanceOf(MemoryConflictException.class)
                .hasMessageContaining(timezone.getId().toString())
                .hasMessageContaining("timezone")
                .hasMessageContaining("rejection")
                .hasMessageNotContaining(entityId.toString());

        assertThat(entity.getStatus()).as("the loser retires nothing").isEqualTo(EntityStatus.PENDING);
        verify(assertions, never()).save(any());
    }

    /**
     * Minor 4 (T10 fix round 1). The entity row carries its own {@code @Version}, but
     * {@code decideEntity} never flushed it: two operator clicks on the same card both pass the
     * PENDING check and the loser surfaces a raw optimistic-lock failure at commit (MCP answers
     * {@code PROPOSAL_DECIDE_FAILED} with that message). The flush inside the decision must use
     * the same governed translation the fact path uses.
     */
    @Test
    void anEntityDecisionWhoseRowVersionWasChangedUnderneathIsAGovernedConflict() {
        UUID entityId = UUID.randomUUID();
        MemoryEntity entity = pendingEntity(entityId);
        when(entities.findById(entityId)).thenReturn(Optional.of(entity));
        when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(entityId, FactStatus.PENDING))
                .thenReturn(List.of());
        doThrow(new ObjectOptimisticLockingFailureException(MemoryEntity.class, entityId))
                .when(entities).flush();

        assertThatThrownBy(() -> proposals.decideProposal(entityId, ProposalDecision.APPROVE, OPERATOR))
                .as("a lost entity version is the governed conflict, not the raw exception")
                .isInstanceOf(MemoryConflictException.class)
                .hasMessageContaining(entityId.toString());

        verify(assertions, never()).save(any());
    }

    /**
     * Approval writes a current ACTIVE value, so it must claim the same entity slot an
     * addFact claims. Without the claim an approval and an addFact on the same (entity,
     * factKey) can both commit a current row; the loser's version bump has to surface as the
     * governed conflict.
     */
    @Test
    void anApprovalThatLosesTheEntitySlotIsAGovernedConflict() {
        UUID entityId = UUID.randomUUID();
        MemoryFact proposed = fact(entityId, "timezone", FactStatus.PENDING, OLD);
        when(facts.findById(proposed.getId())).thenReturn(Optional.of(proposed));
        when(entities.findById(entityId)).thenReturn(Optional.of(entity(entityId)));
        when(entities.save(any(MemoryEntity.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(MemoryEntity.class, entityId));

        assertThatThrownBy(() -> proposals.decideProposal(proposed.getId(), ProposalDecision.APPROVE, OPERATOR))
                .as("a lost slot is a conflict, not a 500")
                .isInstanceOf(MemoryConflictException.class)
                .hasMessageContaining("timezone");

        assertThat(proposed.getStatus()).as("the loser promotes nothing").isEqualTo(FactStatus.PENDING);
        verify(facts, never()).save(any());
        verify(assertions, never()).save(any());
    }

    /**
     * The promoted row carries its own {@code @Version}: a concurrent decision on the same
     * proposal can pass the PENDING check and still collide on the UPDATE. The flush must
     * translate that loss instead of letting it escape at commit.
     */
    @Test
    void anApprovalWhoseRowVersionWasChangedUnderneathIsAGovernedConflict() {
        UUID entityId = UUID.randomUUID();
        MemoryFact proposed = fact(entityId, "timezone", FactStatus.PENDING, OLD);
        when(facts.findById(proposed.getId())).thenReturn(Optional.of(proposed));
        when(entities.findById(entityId)).thenReturn(Optional.of(entity(entityId)));
        when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(entityId, FactStatus.ACTIVE))
                .thenReturn(List.of());
        doThrow(new ObjectOptimisticLockingFailureException(MemoryFact.class, proposed.getId()))
                .when(facts).flush();

        assertThatThrownBy(() -> proposals.decideProposal(proposed.getId(), ProposalDecision.APPROVE, OPERATOR))
                .as("a row-version loss is the governed 409, not a raw exception")
                .isInstanceOf(MemoryConflictException.class)
                .hasMessageContaining("timezone");

        verify(assertions, never()).save(any());
    }

    @Test
    void rejectingAProposalRetiresItAndItCanNeverBecomeActive() {
        MemoryFact proposed = fact(UUID.randomUUID(), "editor", FactStatus.PENDING, OLD);
        when(facts.findById(proposed.getId())).thenReturn(Optional.of(proposed));

        MemoryProposalView decided = proposals.decideProposal(proposed.getId(), ProposalDecision.REJECT, OPERATOR);

        assertThat(decided.status()).isEqualTo("RETIRED");
        assertThat(proposed.getStatus()).isEqualTo(FactStatus.RETIRED);
        assertThat(proposed.getValidTo()).isNotNull();
        ArgumentCaptor<MemoryAssertion> written = ArgumentCaptor.forClass(MemoryAssertion.class);
        verify(assertions).save(written.capture());
        assertThat(written.getValue().getAction()).isEqualTo(MemoryAction.RETIRE);
        assertThatThrownBy(() -> proposals.decideProposal(proposed.getId(), ProposalDecision.APPROVE, OPERATOR))
                .as("a rejected proposal is history, not an approvable subject")
                .isInstanceOf(MemoryConflictException.class);
    }

    @Test
    void decidingTheSameOutcomeTwiceIsIdempotent() {
        MemoryFact proposed = fact(UUID.randomUUID(), "editor", FactStatus.PENDING, OLD);
        when(facts.findById(proposed.getId())).thenReturn(Optional.of(proposed));
        when(entities.findById(proposed.getEntityId())).thenReturn(Optional.of(entity(proposed.getEntityId())));
        when(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(proposed.getEntityId(),
                FactStatus.ACTIVE)).thenReturn(List.of());

        proposals.decideProposal(proposed.getId(), ProposalDecision.APPROVE, OPERATOR);
        MemoryProposalView again = proposals.decideProposal(proposed.getId(), ProposalDecision.APPROVE, OPERATOR);

        assertThat(again.status()).isEqualTo("ACTIVE");
        verify(assertions, times(1)).save(any());
        verify(facts, times(1)).save(proposed);
    }

    @Test
    void aWorkerActorCannotDecideAProposal() {
        UUID subjectId = UUID.randomUUID();

        assertThatThrownBy(() -> proposals.decideProposal(subjectId, ProposalDecision.APPROVE, WORKER_RUN))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> proposals.decideProposal(subjectId, ProposalDecision.APPROVE, WORKER_EXTRACTION))
                .isInstanceOf(SecurityException.class);

        verifyNoInteractions(facts, relations, assertions);
    }

    @Test
    void theInboxDefaultsToPending() {
        when(facts.findByStatusOrderByCreatedAtDesc(eq(FactStatus.PENDING), any(Pageable.class)))
                .thenReturn(List.of(fact(UUID.randomUUID(), "editor", FactStatus.PENDING, OLD)));
        when(relations.findByStatusOrderByCreatedAtDesc(eq(FactStatus.PENDING), any(Pageable.class)))
                .thenReturn(List.of());

        assertThat(proposals.listProposals(null))
                .extracting(MemoryProposalView::status)
                .containsExactly("PENDING");
    }

    @Test
    void aRejectedRelationStaysRetiredAndCannotBeApproved() {
        MemoryRelation proposal = relation(FactStatus.PENDING);
        when(facts.findById(proposal.getId())).thenReturn(Optional.empty());
        when(relations.findById(proposal.getId())).thenReturn(Optional.of(proposal));

        MemoryProposalView decided = proposals.decideProposal(proposal.getId(), ProposalDecision.REJECT, OPERATOR);

        assertThat(decided.status()).isEqualTo("RETIRED");
        assertThat(proposal.getStatus()).isEqualTo(FactStatus.RETIRED);
        assertThat(proposal.getValidTo()).isNotNull();
        ArgumentCaptor<MemoryAssertion> written = ArgumentCaptor.forClass(MemoryAssertion.class);
        verify(assertions).save(written.capture());
        assertThat(written.getValue().getAction()).isEqualTo(MemoryAction.RETIRE);
        assertThat(written.getValue().getSubjectType()).isEqualTo(MemorySubjectType.RELATION);
        assertThatThrownBy(() -> proposals.decideProposal(proposal.getId(), ProposalDecision.APPROVE, OPERATOR))
                .isInstanceOf(MemoryConflictException.class);
    }

    @Test
    void anUnknownSubjectIsNotFound() {
        UUID subjectId = UUID.randomUUID();
        when(facts.findById(subjectId)).thenReturn(Optional.empty());
        when(relations.findById(subjectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> proposals.decideProposal(subjectId, ProposalDecision.APPROVE, OPERATOR))
                .isInstanceOf(MemoryNotFoundException.class);

        verify(assertions, never()).save(any());
    }

    @Test
    void aPendingRelationIsPromotedAndItsDuplicateRetired() {
        MemoryRelation proposal = relation(FactStatus.PENDING);
        MemoryRelation duplicate = relationLike(proposal, FactStatus.ACTIVE);
        when(facts.findById(proposal.getId())).thenReturn(Optional.empty());
        when(relations.findById(proposal.getId())).thenReturn(Optional.of(proposal));
        when(relations.findByFromEntityIdAndStatus(proposal.getFromEntityId(), FactStatus.ACTIVE))
                .thenReturn(List.of(duplicate));

        MemoryProposalView decided = proposals.decideProposal(proposal.getId(), ProposalDecision.APPROVE, OPERATOR);

        assertThat(decided.subjectType()).isEqualTo("RELATION");
        assertThat(decided.predicate()).isEqualTo("OWNS");
        assertThat(decided.status()).isEqualTo("ACTIVE");
        assertThat(proposal.getStatus()).isEqualTo(FactStatus.ACTIVE);
        assertThat(duplicate.getStatus()).isEqualTo(FactStatus.RETIRED);
        ArgumentCaptor<MemoryAssertion> written = ArgumentCaptor.forClass(MemoryAssertion.class);
        verify(assertions, times(2)).save(written.capture());
        assertThat(written.getAllValues()).extracting(MemoryAssertion::getAction)
                .containsExactly(MemoryAction.SUPERSEDE, MemoryAction.UPDATE);
        assertThat(written.getAllValues()).allSatisfy(
                assertion -> assertThat(assertion.getSubjectType()).isEqualTo(MemorySubjectType.RELATION));
    }

    // ---------------------------------------------------------------- transaction boundary

    /**
     * {@code decideProposal} carries {@code @Transactional}. No pin covered it anywhere in the
     * repo: a grep for the method found only callers, and the mutation probe (delete the
     * annotation) left every test green, because the only behavioural test of its rollback drove a
     * {@code new MemoryProposalService(...)} inside its own {@code REQUIRES_NEW} template.
     * <p>
     * The behavioural pin is
     * {@link MemoryCommandServiceTransactionTest#aFailedFactAssertionRollsBackTheWholeEntityRejection}
     * (the service bean, no ambient transaction, so only this annotation can roll the batch back).
     * This static half is the second one the previous wave added for {@code addFactByKey}: the
     * annotation must exist on the declared method itself, so removing it reds a test that never
     * touches a database.
     */
    @Test
    void decideProposalCarriesItsOwnTransactionBoundary() throws Exception {
        Method decideProposal = MemoryProposalService.class.getMethod(
                "decideProposal", UUID.class, ProposalDecision.class, MemoryActor.class);

        assertThat(decideProposal.isAnnotationPresent(Transactional.class))
                .as("decideProposal must run in one transaction")
                .isTrue();
    }

    // ---------------------------------------------------------------- fixtures

    private static MemoryEntity entity(UUID entityId) {
        return MemoryEntity.builder()
                .id(entityId)
                .kind("PERSON")
                .canonicalKey("operator")
                .displayName("operator")
                .sensitivity(Sensitivity.INTERNAL)
                .status(EntityStatus.ACTIVE)
                .origin(MemoryOrigin.OPERATOR)
                .build();
    }

    private static MemoryEntity pendingEntity(UUID entityId) {
        return MemoryEntity.builder()
                .id(entityId)
                .kind("PERSON")
                .canonicalKey("person:happy")
                .displayName("Happy")
                .sensitivity(Sensitivity.INTERNAL)
                .status(EntityStatus.PENDING)
                .origin(MemoryOrigin.EXTRACTION)
                .createdAt(OLD)
                .updatedAt(OLD)
                .build();
    }

    private static MemoryFact fact(UUID entityId, String factKey, FactStatus status, Instant createdAt) {
        return MemoryFact.builder()
                .id(UUID.randomUUID())
                .entityId(entityId)
                .factKey(factKey)
                .valueText("value")
                .valueType("STRING")
                .status(status)
                .origin(status == FactStatus.PENDING ? MemoryOrigin.EXTRACTION : MemoryOrigin.OPERATOR)
                .sensitivity(Sensitivity.INTERNAL)
                .validFrom(createdAt)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
    }

    private static MemoryRelation relation(FactStatus status) {
        return relation(status, OLD);
    }

    private static MemoryRelation relation(FactStatus status, Instant createdAt) {
        return MemoryRelation.builder()
                .id(UUID.randomUUID())
                .fromEntityId(UUID.randomUUID())
                .predicate("OWNS")
                .toEntityId(UUID.randomUUID())
                .status(status)
                .origin(status == FactStatus.PENDING ? MemoryOrigin.EXTRACTION : MemoryOrigin.OPERATOR)
                .sensitivity(Sensitivity.INTERNAL)
                .validFrom(OLD)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
    }

    /** A second edge with the same geometry as {@code proposal} — the conflict case. */
    private static MemoryRelation relationLike(MemoryRelation proposal, FactStatus status) {
        return MemoryRelation.builder()
                .id(UUID.randomUUID())
                .fromEntityId(proposal.getFromEntityId())
                .predicate(proposal.getPredicate())
                .toEntityId(proposal.getToEntityId())
                .status(status)
                .origin(MemoryOrigin.OPERATOR)
                .sensitivity(Sensitivity.INTERNAL)
                .validFrom(OLD)
                .createdAt(OLD)
                .updatedAt(OLD)
                .build();
    }
}
