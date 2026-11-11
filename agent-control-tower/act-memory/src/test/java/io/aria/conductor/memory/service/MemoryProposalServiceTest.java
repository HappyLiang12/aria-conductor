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
     * id is what the decision endpoint is addressed to.
     */
    @Test
    void listProposalsIncludesPendingEntitiesWithTheirKeyAndDisplayName() {
        MemoryEntity proposal = pendingEntity(UUID.randomUUID());
        when(entities.findByStatusOrderByUpdatedAtDesc(eq(EntityStatus.PENDING), any(Pageable.class)))
                .thenReturn(List.of(proposal));

        List<MemoryProposalView> inbox = proposals.listProposals(null);

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
