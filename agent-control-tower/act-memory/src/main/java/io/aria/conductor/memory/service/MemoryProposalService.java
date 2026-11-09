package io.aria.conductor.memory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.memory.dto.MemoryActor;
import io.aria.conductor.memory.dto.MemoryProposalView;
import io.aria.conductor.memory.dto.ProposalDecision;
import io.aria.conductor.memory.model.FactStatus;
import io.aria.conductor.memory.model.MemoryAction;
import io.aria.conductor.memory.model.MemoryActorType;
import io.aria.conductor.memory.model.MemoryAssertion;
import io.aria.conductor.memory.model.MemoryFact;
import io.aria.conductor.memory.model.MemoryRelation;
import io.aria.conductor.memory.model.MemorySubjectType;
import io.aria.conductor.memory.repository.MemoryAssertionRepository;
import io.aria.conductor.memory.repository.MemoryFactRepository;
import io.aria.conductor.memory.repository.MemoryRelationRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The proposal review path (spec §7, §9): list the PENDING facts and relations, then let an
 * operator approve or reject one.
 * <p>
 * Decisions are operator-only — an agent run cannot approve its own proposal, so any other
 * actor is refused with a {@link SecurityException} before a row is read. Approval promotes
 * the subject to ACTIVE and, in the same transaction, retires a conflicting current ACTIVE
 * value instead of leaving two current values. Rejection retires the proposal, so a rejected
 * subject can never become ACTIVE. Repeating the same verdict is a no-op that writes no
 * second assertion; flipping a decided subject is a conflict.
 * <p>
 * The inbox is a bounded, newest-first page: two status-scoped, page-limited queries (one
 * per subject table) are merged and truncated, so reviewing proposals never enumerates the
 * fact and relation history. A row older than the page is reached by narrowing the status
 * or the limit, not by loading everything.
 */
@Service
public class MemoryProposalService {

    private static final ObjectMapper AUDIT = new ObjectMapper();

    private final MemoryFactRepository facts;
    private final MemoryRelationRepository relations;
    private final MemoryAssertionRepository assertions;

    public MemoryProposalService(MemoryFactRepository facts,
                                 MemoryRelationRepository relations,
                                 MemoryAssertionRepository assertions) {
        this.facts = facts;
        this.relations = relations;
        this.assertions = assertions;
    }

    /** The review inbox page size: bounded, newest first. */
    static final int INBOX_LIMIT = 50;

    /** The review inbox: the newest {@link #INBOX_LIMIT} facts and relations in {@code status}. */
    @Transactional(readOnly = true)
    public List<MemoryProposalView> listProposals(FactStatus status) {
        return listProposals(status, INBOX_LIMIT);
    }

    /**
     * One bounded page of the review inbox, newest first. Facts and relations are read with
     * two status-scoped, page-limited queries instead of enumerating both tables, then merged
     * into one page so the reviewer sees a single newest-first list.
     */
    @Transactional(readOnly = true)
    public List<MemoryProposalView> listProposals(FactStatus status, int limit) {
        FactStatus target = status == null ? FactStatus.PENDING : status;
        int pageSize = limit <= 0 ? INBOX_LIMIT : limit;
        Pageable page = PageRequest.of(0, pageSize);
        List<MemoryProposalView> inbox = new ArrayList<>();
        for (MemoryFact fact : safe(facts.findByStatusOrderByCreatedAtDesc(target, page))) {
            if (fact != null) {
                inbox.add(MemoryProposalView.fromFact(fact));
            }
        }
        for (MemoryRelation relation : safe(relations.findByStatusOrderByCreatedAtDesc(target, page))) {
            if (relation != null) {
                inbox.add(MemoryProposalView.fromRelation(relation));
            }
        }
        inbox.sort(Comparator.comparing(MemoryProposalView::createdAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(MemoryProposalView::subjectType)
                .thenComparing(MemoryProposalView::id, Comparator.nullsLast(Comparator.naturalOrder())));
        return inbox.size() > pageSize ? List.copyOf(inbox.subList(0, pageSize)) : inbox;
    }

    /**
     * Decide one proposal. The id addresses the subject itself (a fact or a relation), as
     * the proposals API describes it (spec §9).
     */
    @Transactional
    public MemoryProposalView decideProposal(UUID subjectId, ProposalDecision decision, MemoryActor actor) {
        requireOperator(actor);
        if (subjectId == null) {
            throw new MemoryValidationException("subjectId is required");
        }
        if (decision == null) {
            throw new MemoryValidationException("decision is required");
        }
        Optional<MemoryFact> fact = facts.findById(subjectId);
        if (fact.isPresent()) {
            return decideFact(fact.get(), decision, actor);
        }
        Optional<MemoryRelation> relation = relations.findById(subjectId);
        if (relation.isPresent()) {
            return decideRelation(relation.get(), decision, actor);
        }
        throw new MemoryNotFoundException("memory proposal " + subjectId + " not found");
    }

    private MemoryProposalView decideFact(MemoryFact proposal, ProposalDecision decision, MemoryActor actor) {
        FactStatus outcome = decision == ProposalDecision.APPROVE ? FactStatus.ACTIVE : FactStatus.RETIRED;
        if (proposal.getStatus() == outcome) {
            return MemoryProposalView.fromFact(proposal);
        }
        if (proposal.getStatus() != FactStatus.PENDING) {
            throw new MemoryConflictException("memory fact " + proposal.getId() + " is already "
                    + proposal.getStatus() + "; a decided proposal cannot be reopened");
        }
        Instant now = Instant.now();
        String before = audit("status", FactStatus.PENDING.name(), "factKey", proposal.getFactKey());

        if (decision == ProposalDecision.REJECT) {
            proposal.setStatus(FactStatus.RETIRED);
            proposal.setValidTo(now);
            facts.save(proposal);
            assertions.save(assertion(MemorySubjectType.FACT, proposal.getId(), MemoryAction.RETIRE, actor, now,
                    before, audit("status", FactStatus.RETIRED.name(), "factKey", proposal.getFactKey())));
            return MemoryProposalView.fromFact(proposal);
        }

        // No silent overwrite: a conflicting current value is retired in the same transaction.
        for (MemoryFact current : safe(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(
                proposal.getEntityId(), FactStatus.ACTIVE))) {
            if (current == null || proposal.getId().equals(current.getId())
                    || !Objects.equals(proposal.getFactKey(), current.getFactKey())) {
                continue;
            }
            current.setStatus(FactStatus.RETIRED);
            current.setValidTo(now);
            facts.save(current);
            assertions.save(assertion(MemorySubjectType.FACT, current.getId(), MemoryAction.SUPERSEDE, actor, now,
                    audit("status", FactStatus.ACTIVE.name(), "factKey", current.getFactKey()),
                    audit("status", FactStatus.RETIRED.name(), "factKey", current.getFactKey(),
                            "replacedBy", proposal.getId().toString())));
        }
        proposal.setStatus(FactStatus.ACTIVE);
        proposal.setValidFrom(now);
        facts.save(proposal);
        assertions.save(assertion(MemorySubjectType.FACT, proposal.getId(), MemoryAction.UPDATE, actor, now,
                before, audit("status", FactStatus.ACTIVE.name(), "factKey", proposal.getFactKey())));
        return MemoryProposalView.fromFact(proposal);
    }

    private MemoryProposalView decideRelation(MemoryRelation proposal, ProposalDecision decision,
                                              MemoryActor actor) {
        FactStatus outcome = decision == ProposalDecision.APPROVE ? FactStatus.ACTIVE : FactStatus.RETIRED;
        if (proposal.getStatus() == outcome) {
            return MemoryProposalView.fromRelation(proposal);
        }
        if (proposal.getStatus() != FactStatus.PENDING) {
            throw new MemoryConflictException("memory relation " + proposal.getId() + " is already "
                    + proposal.getStatus() + "; a decided proposal cannot be reopened");
        }
        Instant now = Instant.now();
        String before = audit("status", FactStatus.PENDING.name(), "predicate", proposal.getPredicate());

        if (decision == ProposalDecision.REJECT) {
            proposal.setStatus(FactStatus.RETIRED);
            proposal.setValidTo(now);
            relations.save(proposal);
            assertions.save(assertion(MemorySubjectType.RELATION, proposal.getId(), MemoryAction.RETIRE,
                    actor, now, before, audit("status", FactStatus.RETIRED.name(),
                            "predicate", proposal.getPredicate())));
            return MemoryProposalView.fromRelation(proposal);
        }

        for (MemoryRelation current : safe(relations.findByFromEntityIdAndStatus(
                proposal.getFromEntityId(), FactStatus.ACTIVE))) {
            if (current == null || proposal.getId().equals(current.getId())
                    || !Objects.equals(proposal.getPredicate(), current.getPredicate())
                    || !Objects.equals(proposal.getToEntityId(), current.getToEntityId())) {
                continue;
            }
            current.setStatus(FactStatus.RETIRED);
            current.setValidTo(now);
            relations.save(current);
            assertions.save(assertion(MemorySubjectType.RELATION, current.getId(), MemoryAction.SUPERSEDE,
                    actor, now, audit("status", FactStatus.ACTIVE.name(), "predicate", current.getPredicate()),
                    audit("status", FactStatus.RETIRED.name(), "predicate", current.getPredicate(),
                            "replacedBy", proposal.getId().toString())));
        }
        proposal.setStatus(FactStatus.ACTIVE);
        proposal.setValidFrom(now);
        relations.save(proposal);
        assertions.save(assertion(MemorySubjectType.RELATION, proposal.getId(), MemoryAction.UPDATE, actor, now,
                before, audit("status", FactStatus.ACTIVE.name(), "predicate", proposal.getPredicate())));
        return MemoryProposalView.fromRelation(proposal);
    }

    private static void requireOperator(MemoryActor actor) {
        if (actor == null || actor.type() != MemoryActorType.OPERATOR) {
            throw new SecurityException("deciding a memory proposal is operator-only");
        }
    }

    private static MemoryAssertion assertion(MemorySubjectType subjectType, UUID subjectId, MemoryAction action,
                                             MemoryActor actor, Instant createdAt,
                                             String beforeJson, String afterJson) {
        return MemoryAssertion.builder()
                .id(UUID.randomUUID())
                .subjectType(subjectType)
                .subjectId(subjectId)
                .action(action)
                .actorType(actor.type())
                .actorRef(actor.ref())
                .beforeJson(beforeJson)
                .afterJson(afterJson)
                .createdAt(createdAt)
                .build();
    }

    /** A small JSON audit payload, built from alternating key/value arguments. */
    private static String audit(Object... keysAndValues) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            snapshot.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        try {
            return AUDIT.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static <T> List<T> safe(List<T> rows) {
        return rows == null ? List.of() : rows;
    }
}
