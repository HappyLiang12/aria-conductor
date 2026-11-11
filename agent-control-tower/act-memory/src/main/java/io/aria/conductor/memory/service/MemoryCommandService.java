package io.aria.conductor.memory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The governed write path of memory: one method per mutation, each returning the row it
 * wrote and each writing a {@link MemoryAssertion} in the same transaction.
 * <p>
 * Governance follows the spec matrix. OPERATOR writes land ACTIVE; RUN writes land ACTIVE
 * only for structural fact keys ({@code repo}, {@code branch}, {@code issue}, {@code stack},
 * {@code workspace}) and must carry a provenance reference; EXTRACTION writes land PENDING
 * and are idempotent through {@code proposal_hash}. A second ACTIVE value for the same
 * {@code (entity, fact key)} is a conflict, never an overwrite — replacing a value is an
 * explicit {@link #supersedeFact} that checks the caller's version.
 */
@Service
public class MemoryCommandService {

    private static final Set<String> STRUCTURAL_FACT_KEYS =
            Set.of("repo", "branch", "issue", "stack", "workspace");

    private static final ObjectMapper AUDIT = new ObjectMapper();

    private final MemoryEntityRepository entities;
    private final MemoryFactRepository facts;
    private final MemoryRelationRepository relations;
    private final MemoryKnowledgeLinkRepository links;
    private final MemoryAssertionRepository assertions;
    private final MemoryValueGuard guard;
    private final MemoryProposalWriter proposalWriter;
    private final MemoryCurrentValueSlot currentValueSlot;
    private final ApplicationEventPublisher events;

    public MemoryCommandService(MemoryEntityRepository entities,
                                MemoryFactRepository facts,
                                MemoryRelationRepository relations,
                                MemoryKnowledgeLinkRepository links,
                                MemoryAssertionRepository assertions,
                                MemoryValueGuard guard,
                                MemoryProposalWriter proposalWriter,
                                MemoryCurrentValueSlot currentValueSlot,
                                ApplicationEventPublisher events) {
        this.entities = entities;
        this.facts = facts;
        this.relations = relations;
        this.links = links;
        this.assertions = assertions;
        this.guard = guard;
        this.proposalWriter = proposalWriter;
        this.currentValueSlot = currentValueSlot;
        this.events = events;
    }

    @Transactional
    public MemoryEntity createEntity(CreateEntityCommand command, MemoryActor actor) {
        String kind = requireText(command.kind(), "kind");
        String canonicalKey = requireText(command.canonicalKey(), "canonicalKey");
        guard.checkKey("canonicalKey", canonicalKey);
        guard.checkValue("displayName", command.displayName());
        MemoryOrigin origin = originOf(actor);
        // 2026-11-11 operator decision (PA experience, spec §5.5 / C3 / F10): the EXTRACTION row of
        // the governance matrix may now PROPOSE an entity, but only as PENDING. This amends the
        // earlier reading that "a PENDING entity has no meaning": a PENDING entity is the parent a
        // batch of PENDING facts hangs off, and one operator decision promotes (or retires) the
        // whole batch. What keeps it safe is the read path, not the write — recall and the context
        // contributor query ACTIVE only, listEntities defaults to ACTIVE, getEntity refuses a
        // PENDING entity, and requireEntity refuses an ACTIVE fact against one. So an unapproved
        // entity never reaches recall, a prompt or a default list, while the operator can still see
        // it through the explicit PENDING filter and the proposal inbox.
        EntityStatus status = origin == MemoryOrigin.EXTRACTION ? EntityStatus.PENDING : EntityStatus.ACTIVE;
        Optional<MemoryEntity> existing = entities.findByKindAndCanonicalKey(kind, canonicalKey);
        if (existing.isPresent()) {
            MemoryEntity row = existing.get();
            // 2026-11-11 fix-round decision (T10 review Minor 1): a decided key must not swallow a
            // new proposal. UNIQUE (kind, canonical_key) makes this row the identity of the key, so
            // a second row for the key cannot exist without a migration (this program adds none).
            // Re-proposing therefore resets the identity row to PENDING — never to ACTIVE, because
            // the promotion stays the operator's decision — clears the merge redirect (the key is
            // an open proposal again), re-stamps the proposal's own fields and publishes the card
            // event again. The suppressed verdict stays auditable on the RETIRE/MERGE assertions and
            // on this CREATE assertion's before-json. A PENDING or ACTIVE row is returned unchanged,
            // so a repeated extraction remains a no-op (spec §11) exactly as before.
            if (status == EntityStatus.PENDING && isTerminal(row.getStatus())) {
                return rePropose(row, kind, canonicalKey, command, origin, status, actor);
            }
            return row;
        }
        Instant now = Instant.now();

        MemoryEntity entity = MemoryEntity.builder()
                .id(UUID.randomUUID())
                .kind(kind)
                .canonicalKey(canonicalKey)
                .displayName(isBlank(command.displayName()) ? canonicalKey : command.displayName())
                .sensitivity(command.sensitivity() == null ? Sensitivity.INTERNAL : command.sensitivity())
                .status(status)
                .origin(origin)
                .build();
        try {
            entities.save(entity);
        } catch (DataIntegrityViolationException e) {
            // UNIQUE (kind, canonical_key) is the atomic guard; the loser reads the winner.
            return entities.findByKindAndCanonicalKey(kind, canonicalKey)
                    .orElseThrow(() -> new MemoryConflictException("entity " + kind + "/" + canonicalKey
                            + " collided with a concurrent write that cannot be read back; retry"));
        }
        assertions.save(assertion(MemorySubjectType.ENTITY, entity.getId(), MemoryAction.CREATE, actor,
                null, now, null, audit(
                        "kind", kind,
                        "canonicalKey", canonicalKey,
                        "status", status.name())));
        publishEntityProposal(entity, actor);
        return entity;
    }

    /**
     * Re-proposes a decided key on its identity row (see the comment in {@link #createEntity}).
     * The row becomes PENDING review material again — never ACTIVE — the previous terminal status
     * and merge redirect are recorded on the CREATE assertion, and the card event is published so
     * the operator sees the new proposal.
     */
    private MemoryEntity rePropose(MemoryEntity row, String kind, String canonicalKey,
                                   CreateEntityCommand command, MemoryOrigin origin,
                                   EntityStatus status, MemoryActor actor) {
        Instant now = Instant.now();
        String before = audit(
                "status", row.getStatus().name(),
                "mergedInto", row.getMergedInto() == null ? null : row.getMergedInto().toString());
        row.setStatus(status);
        row.setOrigin(origin);
        row.setDisplayName(isBlank(command.displayName()) ? canonicalKey : command.displayName());
        if (command.sensitivity() != null) {
            // Absence must never widen an existing label: only an explicit label replaces it.
            row.setSensitivity(command.sensitivity());
        }
        row.setMergedInto(null);
        row.setUpdatedAt(now);
        entities.save(row);
        assertions.save(assertion(MemorySubjectType.ENTITY, row.getId(), MemoryAction.CREATE, actor,
                null, now, before, audit(
                        "kind", kind,
                        "canonicalKey", canonicalKey,
                        "status", status.name())));
        publishEntityProposal(row, actor);
        return row;
    }

    private static boolean isTerminal(EntityStatus status) {
        return status == EntityStatus.RETIRED || status == EntityStatus.MERGED;
    }

    @Transactional
    public MemoryFact addFact(UUID entityId, AddFactCommand command, MemoryActor actor) {
        String factKey = requireText(command.factKey(), "factKey");
        guard.checkKey("factKey", factKey);
        guard.checkValue("valueText", command.valueText());
        MemoryOrigin origin = originOf(actor);
        FactStatus status = statusOf(origin, factKey, command.sourceRef());
        requireEntityForFact(entityId, status);
        Instant now = Instant.now();

        if (status == FactStatus.ACTIVE) {
            ensureNoCurrentActiveFact(entityId, factKey);
            currentValueSlot.claim(entityId, factKey, now);
            return insertFact(entityId, factKey, command, actor, origin, status, null, now);
        }

        String proposalHash = proposalHash(origin, entityId, factKey, command.valueText(), command.sourceRef());
        // The repository exposes no proposal-hash lookup, so the probe uses the entity's fact
        // history (all statuses, so a repeat after an operator decision is still a no-op).
        Optional<MemoryFact> alreadyProposed = facts.findByEntityIdOrderByValidFromDesc(entityId).stream()
                .filter(fact -> proposalHash.equals(fact.getProposalHash()))
                .findFirst();
        if (alreadyProposed.isPresent()) {
            return alreadyProposed.get();
        }
        MemoryFact proposal = buildFact(entityId, factKey, command, origin, status, proposalHash, now);
        MemoryAssertion assertion = assertion(MemorySubjectType.FACT, proposal.getId(), MemoryAction.CREATE, actor,
                command.sourceRef(), now, null, audit(
                        "entityId", entityId.toString(),
                        "factKey", factKey,
                        "status", status.name(),
                        "origin", origin.name()));
        return writeProposal(proposal, assertion, proposalHash);
    }

    /**
     * The unique index on {@code proposal_hash} is the atomic guard: a concurrent extraction
     * of the same proposal can pass the probe too. The loser does not fail — it returns the
     * row that won, which is exactly what "a repeated extraction is a no-op" means
     * (spec §11). A violation whose winner cannot be read is still reported as a governed
     * conflict rather than a raw database error.
     */
    private MemoryFact writeProposal(MemoryFact proposal, MemoryAssertion assertion, String proposalHash) {
        try {
            return proposalWriter.insertProposal(proposal, assertion);
        } catch (DataIntegrityViolationException e) {
            return proposalWriter.findExistingProposal(proposal.getEntityId(), proposalHash)
                    .orElseThrow(() -> new MemoryConflictException("proposal for entity "
                            + proposal.getEntityId() + " and key '" + proposal.getFactKey()
                            + "' collided with a concurrent write that cannot be read back; retry"));
        }
    }

    @Transactional
    public MemoryFact supersedeFact(UUID factId, SupersedeFactCommand command, MemoryActor actor) {
        MemoryOrigin origin = originOf(actor);
        if (origin == MemoryOrigin.EXTRACTION) {
            throw new MemoryValidationException(
                    "supersede is an operator or run action; an extraction writes a PENDING proposal instead");
        }
        MemoryFact current = facts.findById(factId)
                .orElseThrow(() -> new MemoryNotFoundException("memory fact " + factId + " not found"));
        if (current.getStatus() != FactStatus.ACTIVE || current.getValidTo() != null) {
            throw new MemoryConflictException("fact " + factId + " is not the current ACTIVE value");
        }
        if (current.getVersion() != command.expectedVersion()) {
            throw new MemoryConflictException("fact " + factId + " moved from the expected version "
                    + command.expectedVersion() + " to " + current.getVersion() + "; re-read before superseding");
        }
        if (origin == MemoryOrigin.RUN) {
            requireStructuralKey(current.getFactKey());
            requireProvenance(command.sourceRef());
        }
        guard.checkValue("valueText", command.valueText());
        Instant now = Instant.now();

        String factKey = current.getFactKey();
        // The fresh row is another current value, so this path claims the same entity slot an
        // addFact claims: that is what stops an addFact or an approval on the same key from
        // committing a second row with valid_to=null.
        currentValueSlot.claim(current.getEntityId(), factKey, now);

        String before = audit("factKey", factKey, "value", current.getValueText(),
                "status", current.getStatus().name());
        current.setStatus(FactStatus.RETIRED);
        current.setValidTo(now);
        facts.save(current);

        MemoryFact fresh = MemoryFact.builder()
                .id(UUID.randomUUID())
                .entityId(current.getEntityId())
                .factKey(factKey)
                .valueText(command.valueText())
                .valueType(current.getValueType())
                .status(FactStatus.ACTIVE)
                .origin(origin)
                .sensitivity(current.getSensitivity())
                .validFrom(now)
                .build();
        facts.save(fresh);
        flushFacts(factKey, "superseded");

        String after = audit("factKey", factKey, "value", command.valueText(),
                "status", FactStatus.ACTIVE.name());
        assertions.save(assertion(MemorySubjectType.FACT, current.getId(), MemoryAction.SUPERSEDE, actor,
                command.sourceRef(), now, before, after));
        assertions.save(assertion(MemorySubjectType.FACT, fresh.getId(), MemoryAction.CREATE, actor,
                command.sourceRef(), now, null, after));
        return fresh;
    }

    /**
     * The governed forget: close the current ACTIVE value and record the RETIRE assertion in
     * one transaction. Operator-only — a run or extraction actor may never remove memory.
     * An already-closed or non-ACTIVE row is a conflict, never a silent second retire, and
     * the row-level version is pushed through the flush so a concurrent change surfaces as
     * the governed conflict inside the method rather than at commit.
     * <p>
     * The audit payload names the key and the lifecycle status, never the value: a fact's
     * value can be CONFIDENTIAL, and the frozen row already records it once.
     */
    @Transactional
    public MemoryFact retireFact(UUID factId, MemoryActor actor) {
        if (actor == null || actor.type() != MemoryActorType.OPERATOR) {
            throw new SecurityException("retiring a memory fact is operator-only");
        }
        if (factId == null) {
            throw new MemoryValidationException("factId is required");
        }
        MemoryFact current = facts.findById(factId)
                .orElseThrow(() -> new MemoryNotFoundException("memory fact " + factId + " not found"));
        if (current.getStatus() != FactStatus.ACTIVE || current.getValidTo() != null) {
            throw new MemoryConflictException("fact " + factId + " is not the current ACTIVE value");
        }
        Instant now = Instant.now();
        String factKey = current.getFactKey();
        String before = audit("factKey", factKey, "status", current.getStatus().name());
        current.setStatus(FactStatus.RETIRED);
        current.setValidTo(now);
        facts.save(current);
        flushFacts(factKey, "retired");
        assertions.save(assertion(MemorySubjectType.FACT, current.getId(), MemoryAction.RETIRE, actor,
                null, now, before, audit("factKey", factKey, "status", FactStatus.RETIRED.name())));
        return current;
    }

    @Transactional
    public MemoryRelation addRelation(AddRelationCommand command, MemoryActor actor) {
        String predicate = requireText(command.predicate(), "predicate");
        MemoryOrigin origin = originOf(actor);
        if (origin == MemoryOrigin.RUN) {
            throw new MemoryValidationException(
                    "a RUN actor may only record structural facts (repo, branch, issue, stack, workspace); a relation is not one");
        }
        MemoryEntity from = requireEntity(command.fromEntityId());
        MemoryEntity to = requireEntity(command.toEntityId());
        // Decision (T10 review Minor 6): this path deliberately does NOT refuse a PENDING from/to
        // entity. A relation is geometry, not read authority — recall expands relations only from
        // ACTIVE matches and re-filters every neighbour through the ACTIVE-only findByIdInAndStatus,
        // the context contributor reads through recall, and no write is authorised by a relation.
        // So an OPERATOR relation into a PENDING entity cannot surface it (pinned by
        // MemoryRecallServiceTest.anActiveRelationIntoAPendingEntityNeverSurfacesItInRecall); the
        // only residue is a dangling relation an operator detail view shows. Refusing here would
        // change an existing write contract for no read-path gain.
        FactStatus status = origin == MemoryOrigin.EXTRACTION ? FactStatus.PENDING : FactStatus.ACTIVE;
        Instant now = Instant.now();

        MemoryRelation relation = MemoryRelation.builder()
                .id(UUID.randomUUID())
                .fromEntityId(from.getId())
                .predicate(predicate)
                .toEntityId(to.getId())
                .status(status)
                .origin(origin)
                .sensitivity(command.sensitivity() == null ? Sensitivity.INTERNAL : command.sensitivity())
                .validFrom(now)
                .build();
        relations.save(relation);
        assertions.save(assertion(MemorySubjectType.RELATION, relation.getId(), MemoryAction.CREATE, actor,
                command.sourceRef(), now, null, audit(
                        "from", from.getId().toString(),
                        "predicate", predicate,
                        "to", to.getId().toString(),
                        "status", status.name())));
        return relation;
    }

    /**
     * Merge is operator-only: the source keeps a {@code merged_into} redirect and every
     * row that referenced it moves to the surviving entity in the same transaction.
     * <p>
     * Provenance rule (one rule for facts, relations, and links): <em>all</em> rows are
     * re-pointed, including closed and RETIRED ones, so the survivor carries the complete
     * history and nothing is left pointing at a redirect. Collisions have an explicit rule:
     * <ul>
     *   <li>when both entities link the same knowledge item,
     *       {@code uq_memory_knowledge_link(entity_id, knowledge_item_id)} allows only one
     *       row: the stronger decision stays (ACTIVE &gt; PROPOSED &gt; RETIRED), the weaker
     *       row is deleted, and its id is recorded on the merge assertion so the discarded
     *       decision stays auditable.</li>
     * </ul>
     */
    @Transactional
    public MemoryEntity mergeEntities(UUID sourceId, UUID targetId, MemoryActor actor) {
        if (actor == null || actor.type() != MemoryActorType.OPERATOR) {
            throw new MemoryValidationException("entity merge is operator-only");
        }
        if (sourceId == null || targetId == null) {
            throw new MemoryValidationException("sourceId and targetId are required");
        }
        if (sourceId.equals(targetId)) {
            throw new MemoryValidationException("an entity cannot be merged into itself");
        }
        MemoryEntity source = requireEntity(sourceId);
        if (source.getStatus() != EntityStatus.ACTIVE) {
            throw new MemoryConflictException(
                    "entity " + sourceId + " is " + source.getStatus() + " and cannot be merged again");
        }
        MemoryEntity target = requireEntity(targetId);
        if (target.getStatus() != EntityStatus.ACTIVE) {
            throw new MemoryConflictException(
                    "target entity " + targetId + " is " + target.getStatus() + "; merge into the surviving ACTIVE entity");
        }
        Instant now = Instant.now();

        MergeTally tally = new MergeTally();
        moveFacts(sourceId, targetId, actor, tally);
        moveRelations(sourceId, targetId, tally);
        moveLinks(sourceId, targetId, tally);

        String before = audit("status", source.getStatus().name(), "mergedInto", null);
        source.setStatus(EntityStatus.MERGED);
        source.setMergedInto(targetId);
        entities.save(source);
        assertions.save(assertion(MemorySubjectType.ENTITY, sourceId, MemoryAction.MERGE, actor,
                null, now, before, audit(
                        "status", EntityStatus.MERGED.name(),
                        "mergedInto", targetId.toString(),
                        "factsMoved", tally.factsMoved,
                        "factsSuperseded", tally.factsSuperseded,
                        "relationsMoved", tally.relationsMoved,
                        "linksMoved", tally.linksMoved,
                        "linksDropped", tally.linksDropped,
                        "linksDroppedIds", tally.droppedLinkIds)));
        return source;
    }

    /**
     * Every fact row, so the survivor's history is complete. A fact key the survivor
     * already holds as the current ACTIVE value is a collision: the survivor's value stays
     * current and the incoming row is closed ({@code valid_to=now}, RETIRED) with a
     * SUPERSEDE assertion, because spec §7 forbids two overlapping current values and
     * forbids a silent overwrite — the operator merge is the explicit replacement decision.
     */
    private void moveFacts(UUID sourceId, UUID targetId, MemoryActor actor, MergeTally tally) {
        Instant now = Instant.now();
        Map<String, MemoryFact> currentOnTarget = new LinkedHashMap<>();
        for (MemoryFact existing : safe(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(
                targetId, FactStatus.ACTIVE))) {
            if (existing != null) {
                currentOnTarget.put(existing.getFactKey(), existing);
            }
        }
        for (MemoryFact fact : safe(facts.findByEntityIdOrderByValidFromDesc(sourceId))) {
            if (fact == null) {
                continue;
            }
            MemoryFact collision = currentOnTarget.get(fact.getFactKey());
            if (collision != null && isCurrent(fact) && isCurrent(collision)) {
                String factKey = fact.getFactKey();
                fact.setStatus(FactStatus.RETIRED);
                fact.setValidTo(now);
                fact.setEntityId(targetId);
                facts.save(fact);
                assertions.save(assertion(MemorySubjectType.FACT, fact.getId(), MemoryAction.SUPERSEDE,
                        actor, null, now,
                        audit("status", FactStatus.ACTIVE.name(), "factKey", factKey,
                                "entityId", sourceId.toString()),
                        audit("status", FactStatus.RETIRED.name(), "factKey", factKey,
                                "entityId", targetId.toString(),
                                "keptFactId", collision.getId().toString())));
                tally.factsSuperseded++;
                continue;
            }
            fact.setEntityId(targetId);
            facts.save(fact);
            tally.factsMoved++;
        }
    }

    private static boolean isCurrent(MemoryFact fact) {
        return fact != null && fact.getStatus() == FactStatus.ACTIVE && fact.getValidTo() == null;
    }

    private void moveRelations(UUID sourceId, UUID targetId, MergeTally tally) {
        for (FactStatus status : FactStatus.values()) {
            for (MemoryRelation relation : safe(relations.findByFromEntityIdAndStatus(sourceId, status))) {
                if (relation == null) {
                    continue;
                }
                relation.setFromEntityId(targetId);
                relations.save(relation);
                tally.relationsMoved++;
            }
            for (MemoryRelation relation : safe(relations.findByToEntityIdAndStatus(sourceId, status))) {
                if (relation == null) {
                    continue;
                }
                relation.setToEntityId(targetId);
                relations.save(relation);
                tally.relationsMoved++;
            }
        }
    }

    /**
     * The unique index allows one row per (entity, knowledge item), so a collision has to
     * drop one of the two. The dropped row is the <em>weaker</em> decision, not simply the
     * source's: a survivor that only RETIRED (or is still PROPOSED on) the item must not
     * discard the source's approved ACTIVE decision. The dropped row's id is recorded on the
     * merge assertion, so the discarded decision stays auditable.
     */
    private void moveLinks(UUID sourceId, UUID targetId, MergeTally tally) {
        Map<UUID, MemoryKnowledgeLink> survivorLinks = new LinkedHashMap<>();
        for (MemoryKnowledgeLink existing : safe(links.findByEntityId(targetId))) {
            if (existing != null) {
                survivorLinks.put(existing.getKnowledgeItemId(), existing);
            }
        }
        for (MemoryKnowledgeLink link : safe(links.findByEntityId(sourceId))) {
            if (link == null) {
                continue;
            }
            MemoryKnowledgeLink survivorLink = survivorLinks.get(link.getKnowledgeItemId());
            if (survivorLink == null) {
                repointLink(link, targetId);
                survivorLinks.put(link.getKnowledgeItemId(), link);
                tally.linksMoved++;
                continue;
            }
            if (linkStrength(link.getStatus()) > linkStrength(survivorLink.getStatus())) {
                // The source carries the stronger decision, so the survivor's weaker row is
                // the one dropped. Delete (and flush) before re-pointing: Hibernate runs
                // updates before deletes, and the unique index would otherwise fire.
                links.delete(survivorLink);
                links.flush();
                repointLink(link, targetId);
                survivorLinks.put(link.getKnowledgeItemId(), link);
                tally.drop(survivorLink);
                continue;
            }
            // The survivor's decision is at least as strong: its row wins and the duplicate
            // is dropped so the unique constraint keeps holding for any later merge.
            links.delete(link);
            tally.drop(link);
        }
    }

    private void repointLink(MemoryKnowledgeLink link, UUID targetId) {
        link.setEntityId(targetId);
        links.save(link);
    }

    /**
     * ACTIVE beats a pending PROPOSED link, which beats RETIRED. The enum declares
     * PROPOSED, ACTIVE, RETIRED, so its ordinal order is not the strength order and the rank
     * is spelled out here.
     */
    private static int linkStrength(MemoryLinkStatus status) {
        if (status == null) {
            return 0;
        }
        return switch (status) {
            case ACTIVE -> 2;
            case PROPOSED -> 1;
            case RETIRED -> 0;
        };
    }

    /** Counts recorded on the merge assertion; not persisted anywhere else. */
    private static final class MergeTally {
        private int factsMoved;
        private int factsSuperseded;
        private int relationsMoved;
        private int linksMoved;
        private int linksDropped;
        private final List<String> droppedLinkIds = new ArrayList<>();

        private void drop(MemoryKnowledgeLink link) {
            linksDropped++;
            droppedLinkIds.add(String.valueOf(link.getId()));
        }
    }

    // ---------------------------------------------------------------- helpers

    private MemoryFact insertFact(UUID entityId, String factKey, AddFactCommand command, MemoryActor actor,
                                  MemoryOrigin origin, FactStatus status, String proposalHash, Instant now) {
        MemoryFact fact = buildFact(entityId, factKey, command, origin, status, proposalHash, now);
        facts.save(fact);
        assertions.save(assertion(MemorySubjectType.FACT, fact.getId(), MemoryAction.CREATE, actor,
                command.sourceRef(), now, null, audit(
                        "entityId", entityId.toString(),
                        "factKey", factKey,
                        "status", status.name(),
                        "origin", origin.name())));
        return fact;
    }

    /** Builds the row only; the caller decides which transaction writes it. */
    private static MemoryFact buildFact(UUID entityId, String factKey, AddFactCommand command,
                                        MemoryOrigin origin, FactStatus status, String proposalHash, Instant now) {
        return MemoryFact.builder()
                .id(UUID.randomUUID())
                .entityId(entityId)
                .factKey(factKey)
                .valueText(command.valueText())
                .valueType(isBlank(command.valueType()) ? "STRING" : command.valueType())
                .status(status)
                .origin(origin)
                .sensitivity(command.sensitivity() == null ? Sensitivity.INTERNAL : command.sensitivity())
                .proposalHash(proposalHash)
                .validFrom(now)
                .build();
    }

    private void ensureNoCurrentActiveFact(UUID entityId, String factKey) {
        boolean exists = facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(entityId, FactStatus.ACTIVE)
                .stream()
                .anyMatch(fact -> factKey.equals(fact.getFactKey()));
        if (exists) {
            throw new MemoryConflictException("an ACTIVE fact for entity " + entityId + " and key '" + factKey
                    + "' already exists; supersede it instead of writing a second value");
        }
    }

    /**
     * Pushes the fact writes through the row-level {@code @Version} inside the method, so a
     * concurrent supersede/approval that changed the same row surfaces as the governed
     * conflict here instead of as a raw optimistic-lock failure at transaction commit.
     */
    private void flushFacts(String factKey, String operation) {
        try {
            facts.flush();
        } catch (OptimisticLockingFailureException e) {
            throw new MemoryConflictException("the current value for '" + factKey
                    + "' changed while it was being " + operation + "; re-read before retrying");
        }
    }

    private MemoryEntity requireEntity(UUID entityId) {
        if (entityId == null) {
            throw new MemoryValidationException("entityId is required");
        }
        return entities.findById(entityId)
                .orElseThrow(() -> new MemoryNotFoundException("memory entity " + entityId + " not found"));
    }

    /**
     * The dependent-read guard for facts: a PENDING entity is a proposal, so it may only carry
     * PENDING facts (the batch the operator decides together). An ACTIVE fact against it would be
     * current memory whose parent is invisible to recall and to the prompt — exactly the
     * read-path leak spec §5.5 forbids — so it is a conflict, not an implicit promotion.
     */
    private MemoryEntity requireEntityForFact(UUID entityId, FactStatus factStatus) {
        MemoryEntity entity = requireEntity(entityId);
        if (factStatus == FactStatus.ACTIVE && entity.getStatus() == EntityStatus.PENDING) {
            throw new MemoryConflictException("entity " + entityId + " is PENDING; an ACTIVE fact cannot be "
                    + "attached before the entity proposal is approved");
        }
        return entity;
    }

    /**
     * A run-created PENDING entity is the only producer of
     * {@link MemoryProposalCreatedEvent}: the card that asks the operator to decide it renders
     * in the run's stream, so the event is published only when the entity stayed PENDING and the
     * actor carries a run id. The actor reference of a worker is
     * {@code ActorPrincipal.runId().toString()} (MemoryTools), so a reference that is not a UUID
     * is not addressable to a stream and publishes nothing rather than a null-address event.
     */
    private void publishEntityProposal(MemoryEntity entity, MemoryActor actor) {
        if (entity.getStatus() != EntityStatus.PENDING) {
            return;
        }
        UUID runId = runIdOf(actor);
        if (runId == null) {
            return;
        }
        events.publishEvent(new MemoryProposalCreatedEvent(this, entity.getId(),
                MemorySubjectType.ENTITY.name(), entity.getId(), entity.getCanonicalKey(),
                entity.getDisplayName(),
                entity.getSensitivity() == null ? null : entity.getSensitivity().name(), runId));
    }

    private static UUID runIdOf(MemoryActor actor) {
        if (actor == null || isBlank(actor.ref())) {
            return null;
        }
        try {
            return UUID.fromString(actor.ref().trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static MemoryOrigin originOf(MemoryActor actor) {
        if (actor == null) {
            throw new MemoryValidationException("memory actor is required");
        }
        return switch (actor.type()) {
            case OPERATOR -> MemoryOrigin.OPERATOR;
            case RUN -> MemoryOrigin.RUN;
            case EXTRACTION -> MemoryOrigin.EXTRACTION;
            case SYSTEM -> throw new MemoryValidationException(
                    "a SYSTEM actor does not write memory; use OPERATOR for an operator action or EXTRACTION for inference");
        };
    }

    private static FactStatus statusOf(MemoryOrigin origin, String factKey, String sourceRef) {
        return switch (origin) {
            case OPERATOR -> FactStatus.ACTIVE;
            case RUN -> {
                requireStructuralKey(factKey);
                requireProvenance(sourceRef);
                yield FactStatus.ACTIVE;
            }
            case EXTRACTION -> FactStatus.PENDING;
        };
    }

    private static void requireStructuralKey(String factKey) {
        if (!STRUCTURAL_FACT_KEYS.contains(factKey)) {
            throw new MemoryValidationException(
                    "a RUN actor may only record structural facts (repo, branch, issue, stack, workspace); '"
                            + factKey + "' is not one");
        }
    }

    private static void requireProvenance(String sourceRef) {
        if (isBlank(sourceRef)) {
            throw new MemoryValidationException("sourceRef is required as provenance for a RUN write");
        }
    }

    /**
     * Idempotency key of an extraction proposal. Only EXTRACTION writes get one: an
     * operator or run write must fail loudly on a repeat (conflict), not silently return
     * the previous row.
     */
    private static String proposalHash(MemoryOrigin origin, UUID entityId, String factKey,
                                       String valueText, String sourceRef) {
        String material = String.join("|", origin.name(), entityId.toString(), factKey,
                String.valueOf(valueText), String.valueOf(sourceRef));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for proposal idempotency", e);
        }
    }

    private static MemoryAssertion assertion(MemorySubjectType subjectType, UUID subjectId, MemoryAction action,
                                             MemoryActor actor, String sourceRef, Instant createdAt,
                                             String beforeJson, String afterJson) {
        return MemoryAssertion.builder()
                .id(UUID.randomUUID())
                .subjectType(subjectType)
                .subjectId(subjectId)
                .action(action)
                .actorType(actor.type())
                .actorRef(actor.ref())
                .sourceRef(sourceRef)
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

    private static String requireText(String value, String field) {
        if (isBlank(value)) {
            throw new MemoryValidationException(field + " is required");
        }
        return value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static <T> List<T> safe(List<T> rows) {
        return rows == null ? List.of() : rows;
    }
}
