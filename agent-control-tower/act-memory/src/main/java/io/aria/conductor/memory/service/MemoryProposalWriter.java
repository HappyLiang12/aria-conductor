package io.aria.conductor.memory.service;

import io.aria.conductor.memory.model.MemoryAssertion;
import io.aria.conductor.memory.model.MemoryFact;
import io.aria.conductor.memory.repository.MemoryAssertionRepository;
import io.aria.conductor.memory.repository.MemoryFactRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * The proposal insert in its own transaction, so losing the {@code uq_memory_fact_proposal}
 * race is survivable.
 * <p>
 * Extraction proposals are idempotent through {@code proposal_hash}: the unique index is
 * the only atomic guard, and two concurrent extractions of the same proposal can both pass
 * the "does it exist yet" probe. When the loser's insert violates the index, the enclosing
 * transaction is already unusable (the driver and Hibernate mark it for rollback), so the
 * insert — and the read that resolves the winner — each need a fresh transaction. That is
 * what this collaborator provides: {@link #insertProposal} runs in {@code REQUIRES_NEW} and
 * {@link #findExistingProposal} reads in a new transaction, so the winner's committed row
 * is visible even under REPEATABLE READ.
 */
@Component
class MemoryProposalWriter {

    private final MemoryFactRepository facts;
    private final MemoryAssertionRepository assertions;

    MemoryProposalWriter(MemoryFactRepository facts, MemoryAssertionRepository assertions) {
        this.facts = facts;
        this.assertions = assertions;
    }

    /** Write one proposal and its assertion atomically; a duplicate hash surfaces as a violation. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MemoryFact insertProposal(MemoryFact fact, MemoryAssertion assertion) {
        // saveAndFlush forces the unique index check inside this transaction, so the caller
        // learns about the collision here instead of at an unrelated commit.
        facts.saveAndFlush(fact);
        assertions.save(assertion);
        return fact;
    }

    /** Re-read the proposal that won the race, in a transaction that can see it. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<MemoryFact> findExistingProposal(UUID entityId, String proposalHash) {
        if (proposalHash == null) {
            return Optional.empty();
        }
        return facts.findByEntityIdOrderByValidFromDesc(entityId).stream()
                .filter(fact -> fact != null && proposalHash.equals(fact.getProposalHash()))
                .findFirst();
    }
}
