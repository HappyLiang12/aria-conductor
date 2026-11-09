package io.aria.conductor.memory.service;

import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.memory.dto.MemoryActor;
import io.aria.conductor.memory.dto.MemoryProposalView;
import io.aria.conductor.memory.dto.ProposalDecision;
import io.aria.conductor.memory.model.FactStatus;
import io.aria.conductor.memory.model.MemoryAction;
import io.aria.conductor.memory.model.MemoryActorType;
import io.aria.conductor.memory.model.MemoryAssertion;
import io.aria.conductor.memory.model.MemoryFact;
import io.aria.conductor.memory.model.MemoryOrigin;
import io.aria.conductor.memory.model.MemoryRelation;
import io.aria.conductor.memory.model.MemorySubjectType;
import io.aria.conductor.memory.repository.MemoryAssertionRepository;
import io.aria.conductor.memory.repository.MemoryFactRepository;
import io.aria.conductor.memory.repository.MemoryRelationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    MemoryProposalService proposals;

    @BeforeEach
    void setUp() {
        proposals = new MemoryProposalService(facts, relations, assertions);
    }

    @Test
    void listProposalsReturnsPendingFactsAndRelationsOnly() {
        MemoryFact pendingFact = fact(UUID.randomUUID(), "editor", FactStatus.PENDING, OLD);
        MemoryFact activeFact = fact(pendingFact.getEntityId(), "timezone", FactStatus.ACTIVE, OLD);
        MemoryRelation pendingRelation = relation(FactStatus.PENDING);
        MemoryRelation activeRelation = relation(FactStatus.ACTIVE);
        when(facts.findAll()).thenReturn(List.of(activeFact, pendingFact));
        when(relations.findAll()).thenReturn(List.of(activeRelation, pendingRelation));

        List<MemoryProposalView> pending = proposals.listProposals(FactStatus.PENDING);

        assertThat(pending).extracting(MemoryProposalView::subjectType)
                .containsExactly("FACT", "RELATION");
        assertThat(pending.get(0).factKey()).isEqualTo("editor");
        assertThat(pending.get(0).status()).isEqualTo("PENDING");
        assertThat(pending.get(0).origin()).isEqualTo("EXTRACTION");
        assertThat(pending.get(1).predicate()).isEqualTo("OWNS");
        assertThat(pending.get(1).status()).isEqualTo("PENDING");
    }

    @Test
    void approvingAProposalRetiresTheConflictingActiveValueAndPromotesTheProposal() {
        UUID entityId = UUID.randomUUID();
        MemoryFact active = fact(entityId, "timezone", FactStatus.ACTIVE, OLD);
        MemoryFact proposed = fact(entityId, "timezone", FactStatus.PENDING, OLD);
        when(facts.findById(proposed.getId())).thenReturn(Optional.of(proposed));
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

    @Test
    void approvingWithoutAConflictWritesOneAssertion() {
        MemoryFact proposed = fact(UUID.randomUUID(), "timezone", FactStatus.PENDING, OLD);
        when(facts.findById(proposed.getId())).thenReturn(Optional.of(proposed));
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
        when(facts.findAll()).thenReturn(List.of(fact(UUID.randomUUID(), "editor", FactStatus.PENDING, OLD)));
        when(relations.findAll()).thenReturn(List.of());

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
        return MemoryRelation.builder()
                .id(UUID.randomUUID())
                .fromEntityId(UUID.randomUUID())
                .predicate("OWNS")
                .toEntityId(UUID.randomUUID())
                .status(status)
                .origin(status == FactStatus.PENDING ? MemoryOrigin.EXTRACTION : MemoryOrigin.OPERATOR)
                .sensitivity(Sensitivity.INTERNAL)
                .validFrom(OLD)
                .createdAt(OLD)
                .updatedAt(OLD)
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
