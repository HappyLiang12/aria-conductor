package io.aria.conductor.memory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import io.aria.conductor.memory.model.MemoryRelation;
import io.aria.conductor.memory.model.MemorySubjectType;
import io.aria.conductor.memory.repository.MemoryAssertionRepository;
import io.aria.conductor.memory.repository.MemoryEntityRepository;
import io.aria.conductor.memory.repository.MemoryFactRepository;
import io.aria.conductor.memory.repository.MemoryRelationRepository;
import org.springframework.dao.OptimisticLockingFailureException;
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
 * The proposal review path (spec §7, §9): list the PENDING facts, relations and entities, then let
 * an operator approve or reject one. An entity proposal is a batch — its id addresses the entity,
 * and one verdict promotes or retires every PENDING fact that hangs off it (spec §5.5).
 * <p>
 * Decisions are operator-only — an agent run cannot approve its own proposal, so any other
 * actor is refused with a {@link SecurityException} before a row is read. Approval promotes
 * the subject to ACTIVE and, in the same transaction, retires a conflicting current ACTIVE
 * value instead of leaving two current values. Rejection retires the proposal, so a rejected
 * subject can never become ACTIVE. Repeating the same verdict is a no-op that writes no
 * second assertion; flipping a decided subject is a conflict.
 * <p>
 * The inbox is a bounded, newest-first page: three status-scoped, page-limited queries (facts,
 * relations and entities) are merged and truncated, so reviewing proposals never enumerates the
 * fact and relation history. A row older than the page is reached by narrowing the status
 * or the limit, not by loading everything.
 */
@Service
public class MemoryProposalService {

    private static final ObjectMapper AUDIT = new ObjectMapper();

    private final MemoryFactRepository facts;
    private final MemoryRelationRepository relations;
    private final MemoryEntityRepository entities;
    private final MemoryAssertionRepository assertions;
    private final MemoryCurrentValueSlot currentValueSlot;

    public MemoryProposalService(MemoryFactRepository facts,
                                 MemoryRelationRepository relations,
                                 MemoryEntityRepository entities,
                                 MemoryAssertionRepository assertions,
                                 MemoryCurrentValueSlot currentValueSlot) {
        this.facts = facts;
        this.relations = relations;
        this.entities = entities;
        this.assertions = assertions;
        this.currentValueSlot = currentValueSlot;
    }

    /** The review inbox page size: bounded, newest first. */
    static final int INBOX_LIMIT = 50;

    /** The review inbox: the newest {@link #INBOX_LIMIT} facts and relations in {@code status}. */
    @Transactional(readOnly = true)
    public List<MemoryProposalView> listProposals(FactStatus status) {
        return listProposals(status, INBOX_LIMIT, null);
    }

    /**
     * One bounded page of the review inbox, newest first. Facts and relations are read with
     * two status-scoped, page-limited queries instead of enumerating both tables, then merged
     * into one page so the reviewer sees a single newest-first list.
     */
    @Transactional(readOnly = true)
    public List<MemoryProposalView> listProposals(FactStatus status, int limit) {
        return listProposals(status, limit, null);
    }

    /** The review inbox read on behalf of {@code actor} (see {@link #listProposals(FactStatus, int, MemoryActor)}). */
    @Transactional(readOnly = true)
    public List<MemoryProposalView> listProposals(FactStatus status, MemoryActor actor) {
        return listProposals(status, INBOX_LIMIT, actor);
    }

    /**
     * One bounded page of the review inbox, newest first, read on behalf of {@code actor}.
     * <p>
     * T11 C1: {@code list_memory_proposals} is an auto-approved worker read, and a PENDING entity
     * row carries the canonical key and display name that {@code list_memory_entities(status=
     * PENDING)} refuses to the same worker — the guard was cosmetic while this read was actorless.
     * The rule is therefore part of the read contract: a PENDING ENTITY row is included only for
     * an OPERATOR actor. PENDING FACT and RELATION rows stay visible to every actor — a worker
     * must see the proposal it just made. A null actor (no transport identity) is treated as a
     * non-operator, so a missing identity can never widen the read.
     */
    @Transactional(readOnly = true)
    public List<MemoryProposalView> listProposals(FactStatus status, int limit, MemoryActor actor) {
        FactStatus target = status == null ? FactStatus.PENDING : status;
        int pageSize = limit <= 0 ? INBOX_LIMIT : limit;
        Pageable page = PageRequest.of(0, pageSize);
        boolean operator = actor != null && actor.type() == MemoryActorType.OPERATOR;
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
        for (MemoryEntity entity : safe(entities.findByStatusOrderByUpdatedAtDesc(entityStatusOf(target), page))) {
            if (entity != null && (operator || entity.getStatus() != EntityStatus.PENDING)) {
                inbox.add(MemoryProposalView.fromEntity(entity));
            }
        }
        return pageOf(inbox, pageSize);
    }

    /**
     * One entity's own proposal batch: every FACT of {@code entityId} in {@code status} plus the
     * entity row itself when its lifecycle status matches. The batch is the entity's PENDING facts
     * and nothing else, because that is what {@link #decideEntity} promotes and retires (spec
     * :116-117, :323-324); the relation graph is out of scope for this program (spec :138, :453).
     * <p>
     * T29 Item 1 (P1): the read used to add the entity's PENDING relations in both directions, so
     * the card showed a batch the decision did not cover — approving the entity left a relation
     * PENDING, and approving that relation then left it ACTIVE under a RETIRED entity. The
     * relation branch is gone; a relation decision is still reachable through the global inbox,
     * it is just not part of an entity's batch.
     * <p>
     * M1 (UI surface review): the ENTITY card used to filter ONE {@link #INBOX_LIMIT}-row page of
     * the merged global inbox client-side, so with more than 50 PENDING facts across entities an
     * entity's own facts fell off the page and the card claimed "approving adds the entity alone"
     * while {@link #decideEntity} promoted every one of them. The filter is therefore a query
     * (entity + status), never a post-pagination slice, and it is deliberately unbounded: the
     * decision promotes the entity's whole PENDING batch, so a truncated read would under-report
     * what one click does. The actor rule of the inbox is unchanged — a PENDING ENTITY row is
     * included only for an OPERATOR (T11 C1).
     * <p>
     * T31 Item 2: the PENDING read is {@link #decideEntity}'s own query, {@code valid_to IS NULL}
     * included, so the batch equals the set the decision decides by construction. That equality must
     * not rest on an invariant nothing enforces: no shipped writer leaves a PENDING row with a
     * stamped {@code valid_to} — every {@code setValidTo} site pairs it with a terminal status — but
     * the schema permits the combination, and if such a row ever appeared the card would report a
     * decision the click does not make (the same under-reporting M1 fixed, one predicate later).
     * The ACTIVE and RETIRED variants are history views rather than batches and keep the unfiltered
     * query: a RETIRED row always carries {@code valid_to}, so the decision predicate would empty
     * that read.
     */
    @Transactional(readOnly = true)
    public List<MemoryProposalView> listProposalsForEntity(FactStatus status, UUID entityId, MemoryActor actor) {
        if (entityId == null) {
            throw new MemoryValidationException("entityId is required");
        }
        FactStatus target = status == null ? FactStatus.PENDING : status;
        boolean operator = actor != null && actor.type() == MemoryActorType.OPERATOR;
        List<MemoryFact> rows = target == FactStatus.PENDING
                ? safe(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(entityId, target))
                : safe(facts.findByEntityIdAndStatusOrderByCreatedAtDesc(entityId, target));
        List<MemoryProposalView> batch = new ArrayList<>();
        for (MemoryFact fact : rows) {
            if (fact != null) {
                batch.add(MemoryProposalView.fromFact(fact));
            }
        }
        MemoryEntity entity = entities.findById(entityId).orElse(null);
        if (entity != null && entity.getStatus() == entityStatusOf(target)
                && (operator || entity.getStatus() != EntityStatus.PENDING)) {
            batch.add(MemoryProposalView.fromEntity(entity));
        }
        return List.copyOf(batch.stream().sorted(NEWEST_FIRST).toList());
    }

    /** Newest first, then subject type and id so the order is deterministic. */
    private static final Comparator<MemoryProposalView> NEWEST_FIRST =
            Comparator.comparing(MemoryProposalView::createdAt,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(MemoryProposalView::subjectType)
                    .thenComparing(MemoryProposalView::id, Comparator.nullsLast(Comparator.naturalOrder()));

    private static List<MemoryProposalView> pageOf(List<MemoryProposalView> inbox, int pageSize) {
        inbox.sort(NEWEST_FIRST);
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
        Optional<MemoryEntity> entity = entities.findById(subjectId);
        if (entity.isPresent()) {
            return decideEntity(entity.get(), decision, actor);
        }
        throw new MemoryNotFoundException("memory proposal " + subjectId + " not found");
    }

    /**
     * An entity proposal is the whole batch: approve promotes every PENDING fact of the entity,
     * reject retires every one of them, and both write an ENTITY assertion for the decision. The
     * facts go through {@link #decideFact}, so the supersede and slot-claim rules are shared with a
     * single-fact decision instead of re-implemented here. The entity itself claims nothing on the
     * current-value slot — it has no fact key; only the promoted facts do.
     */
    private MemoryProposalView decideEntity(MemoryEntity entity, ProposalDecision decision, MemoryActor actor) {
        EntityStatus outcome = decision == ProposalDecision.APPROVE ? EntityStatus.ACTIVE : EntityStatus.RETIRED;
        if (entity.getStatus() == outcome) {
            return MemoryProposalView.fromEntity(entity);
        }
        if (entity.getStatus() != EntityStatus.PENDING) {
            throw new MemoryConflictException("memory entity " + entity.getId() + " is already "
                    + entity.getStatus() + "; a decided proposal cannot be reopened");
        }
        Instant now = Instant.now();
        String before = audit("status", EntityStatus.PENDING.name(), "kind", entity.getKind(),
                "canonicalKey", entity.getCanonicalKey());
        // "The same batch" is every PENDING fact of this PENDING entity: memory_facts has no batch
        // column and proposal_hash is per fact (spec §5.5).
        List<MemoryFact> batch = safe(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(
                entity.getId(), FactStatus.PENDING));

        if (decision == ProposalDecision.REJECT) {
            for (MemoryFact fact : batch) {
                if (fact != null) {
                    decideFact(fact, ProposalDecision.REJECT, actor);
                }
            }
            entity.setStatus(EntityStatus.RETIRED);
            entities.save(entity);
            flushEntity(entity);
            assertions.save(assertion(MemorySubjectType.ENTITY, entity.getId(), MemoryAction.RETIRE, actor, now,
                    before, audit("status", EntityStatus.RETIRED.name(), "kind", entity.getKind(),
                            "canonicalKey", entity.getCanonicalKey(), "factsRetired", batch.size())));
            return MemoryProposalView.fromEntity(entity);
        }

        entity.setStatus(EntityStatus.ACTIVE);
        entities.save(entity);
        flushEntity(entity);
        assertions.save(assertion(MemorySubjectType.ENTITY, entity.getId(), MemoryAction.UPDATE, actor, now,
                before, audit("status", EntityStatus.ACTIVE.name(), "kind", entity.getKind(),
                        "canonicalKey", entity.getCanonicalKey(), "factsPromoted", batch.size())));
        for (MemoryFact fact : batch) {
            if (fact != null) {
                decideFact(fact, ProposalDecision.APPROVE, actor);
            }
        }
        return MemoryProposalView.fromEntity(entity);
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
            // N3 (T10 fix round 1 re-review): flush here, not only at the entity flush that follows
            // a batch rejection. Otherwise a lost fact version is reported as a lost entity row and
            // the conflict points the operator at the wrong card during an incident.
            flushFacts(proposal, "the rejection");
            assertions.save(assertion(MemorySubjectType.FACT, proposal.getId(), MemoryAction.RETIRE, actor, now,
                    before, audit("status", FactStatus.RETIRED.name(), "factKey", proposal.getFactKey())));
            return MemoryProposalView.fromFact(proposal);
        }

        // The inbox lists a PENDING entity's facts individually, but the entity decision is the
        // batch that owns them. Promoting one fact here would make it ACTIVE under a parent no
        // read path can see (recall, the prompt, the default list and the detail read all exclude
        // a PENDING entity), and a later entity rejection would retire only PENDING rows, leaving
        // it orphaned under a RETIRED parent. Refuse, and tell the operator which card decides it
        // (T10 fix round 1, M1).
        // N2 (T10 fix round 1 re-review): the invariant is "current memory has an ACTIVE parent",
        // not "not PENDING". A proposal raised under an ACTIVE entity can survive its parent being
        // retired or merged, and approving it then produced the same invisible ACTIVE value under a
        // dead parent. Every non-ACTIVE parent is refused, and the message names which case it is.
        // T29 Item 2: the command path enforces the same invariant — requireEntityForFact refuses an
        // ACTIVE write under any non-ACTIVE parent, so parity is now real on both paths.
        MemoryEntity parent = entities.findById(proposal.getEntityId())
                .orElseThrow(() -> new MemoryNotFoundException(
                        "memory entity " + proposal.getEntityId() + " not found"));
        if (parent.getStatus() != EntityStatus.ACTIVE) {
            String reason = parent.getStatus() == EntityStatus.PENDING
                    ? "which is still PENDING; decide the entity proposal " + proposal.getEntityId()
                    + " to promote or retire the whole batch instead of approving one fact"
                    : "which is " + parent.getStatus()
                    + "; the entity is no longer active, so approving this fact would make it "
                    + "current memory under a parent no read path can see";
            throw new MemoryConflictException("memory fact " + proposal.getId()
                    + " belongs to entity " + proposal.getEntityId() + ", " + reason);
        }

        // An approval opens a current ACTIVE value, so it claims the same entity slot an
        // addFact or a supersede claims: without it an approval racing an addFact on the
        // same (entity, factKey) can commit two rows with valid_to=null.
        currentValueSlot.claim(proposal.getEntityId(), proposal.getFactKey(), now);

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
        flushFacts(proposal);
        assertions.save(assertion(MemorySubjectType.FACT, proposal.getId(), MemoryAction.UPDATE, actor, now,
                before, audit("status", FactStatus.ACTIVE.name(), "factKey", proposal.getFactKey())));
        return MemoryProposalView.fromFact(proposal);
    }

    /**
     * Pushes the fact writes through the row-level {@code @Version} inside the method, so a
     * concurrent decision on the same proposal (or a supersede of the conflicting row)
     * surfaces as the governed conflict here instead of as a raw optimistic-lock failure at
     * transaction commit.
     */
    private void flushFacts(MemoryFact proposal) {
        flushFacts(proposal, "the approval (the proposal or its conflicting current value)");
    }

    /**
     * The shared flush translation. {@code what} names the write whose row actually lost the
     * version race, so the governed conflict is attributable (N3, T10 fix round 1 re-review).
     */
    private void flushFacts(MemoryFact proposal, String what) {
        try {
            facts.flush();
        } catch (OptimisticLockingFailureException e) {
            throw new MemoryConflictException("memory fact " + proposal.getId()
                    + " for key '" + proposal.getFactKey()
                    + "' changed while " + what + " was being written; re-read before deciding");
        }
    }

    /**
     * Pushes the entity write through its row-level {@code @Version} inside the method, so a
     * second operator click on the same card (or a decision racing a rename or merge) surfaces as
     * the governed conflict here instead of as a raw optimistic-lock failure at commit — which
     * MCP would report as {@code PROPOSAL_DECIDE_FAILED} with the driver message. Mirrors
     * {@link #flushFacts}; the entity decision may have an empty fact batch, so it cannot rely on
     * the fact flush having run.
     */
    private void flushEntity(MemoryEntity entity) {
        try {
            entities.flush();
        } catch (OptimisticLockingFailureException e) {
            throw new MemoryConflictException("memory entity " + entity.getId()
                    + " changed while its proposal was being decided; re-read before deciding");
        }
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

    /** The inbox status is shared by facts and relations; entities carry their own lifecycle enum. */
    private static EntityStatus entityStatusOf(FactStatus status) {
        return switch (status) {
            case PENDING -> EntityStatus.PENDING;
            case ACTIVE -> EntityStatus.ACTIVE;
            case RETIRED -> EntityStatus.RETIRED;
        };
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
