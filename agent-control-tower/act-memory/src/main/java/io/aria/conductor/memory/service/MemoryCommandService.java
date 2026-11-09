package io.aria.conductor.memory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import io.aria.conductor.memory.model.MemoryOrigin;
import io.aria.conductor.memory.model.MemoryRelation;
import io.aria.conductor.memory.model.MemorySubjectType;
import io.aria.conductor.memory.repository.MemoryAssertionRepository;
import io.aria.conductor.memory.repository.MemoryEntityRepository;
import io.aria.conductor.memory.repository.MemoryFactRepository;
import io.aria.conductor.memory.repository.MemoryRelationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
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

    /** The relation statuses a merge re-points: history (RETIRED) keeps its old subject. */
    private static final List<FactStatus> LIVE_RELATION_STATUSES =
            List.of(FactStatus.ACTIVE, FactStatus.PENDING);

    private static final ObjectMapper AUDIT = new ObjectMapper();

    private final MemoryEntityRepository entities;
    private final MemoryFactRepository facts;
    private final MemoryRelationRepository relations;
    private final MemoryAssertionRepository assertions;
    private final MemoryValueGuard guard;

    public MemoryCommandService(MemoryEntityRepository entities,
                                MemoryFactRepository facts,
                                MemoryRelationRepository relations,
                                MemoryAssertionRepository assertions,
                                MemoryValueGuard guard) {
        this.entities = entities;
        this.facts = facts;
        this.relations = relations;
        this.assertions = assertions;
        this.guard = guard;
    }

    @Transactional
    public MemoryEntity createEntity(CreateEntityCommand command, MemoryActor actor) {
        String kind = requireText(command.kind(), "kind");
        String canonicalKey = requireText(command.canonicalKey(), "canonicalKey");
        guard.checkKey("canonicalKey", canonicalKey);
        guard.checkValue("displayName", command.displayName());
        MemoryOrigin origin = originOf(actor);
        if (origin == MemoryOrigin.EXTRACTION) {
            // Spec §7: the EXTRACTION row of the governance matrix covers preferences,
            // identity facts, relations and new links — all PENDING. An entity is a
            // structural row: an inference may never mint an immediately recallable
            // ACTIVE entity, and a PENDING entity has no meaning (nothing could hang off
            // it). Operator and run/backfill writes are the only entity creators.
            throw new MemoryValidationException(
                    "an EXTRACTION actor cannot create a memory entity; create it as OPERATOR and let the extraction propose facts, relations or links");
        }
        Instant now = Instant.now();

        MemoryEntity entity = MemoryEntity.builder()
                .id(UUID.randomUUID())
                .kind(kind)
                .canonicalKey(canonicalKey)
                .displayName(isBlank(command.displayName()) ? canonicalKey : command.displayName())
                .sensitivity(command.sensitivity() == null ? Sensitivity.INTERNAL : command.sensitivity())
                .status(EntityStatus.ACTIVE)
                .origin(origin)
                .build();
        entities.save(entity);
        assertions.save(assertion(MemorySubjectType.ENTITY, entity.getId(), MemoryAction.CREATE, actor,
                null, now, null, audit(
                        "kind", kind,
                        "canonicalKey", canonicalKey,
                        "status", EntityStatus.ACTIVE.name())));
        return entity;
    }

    @Transactional
    public MemoryFact addFact(UUID entityId, AddFactCommand command, MemoryActor actor) {
        String factKey = requireText(command.factKey(), "factKey");
        guard.checkKey("factKey", factKey);
        guard.checkValue("valueText", command.valueText());
        MemoryOrigin origin = originOf(actor);
        FactStatus status = statusOf(origin, factKey, command.sourceRef());
        requireEntity(entityId);
        Instant now = Instant.now();

        if (status == FactStatus.ACTIVE) {
            ensureNoCurrentActiveFact(entityId, factKey);
            return insertFact(entityId, factKey, command, actor, origin, status, null, now);
        }

        String proposalHash = proposalHash(origin, entityId, factKey, command.valueText(), command.sourceRef());
        // The repository exposes no proposal-hash lookup, so the probe uses the entity's fact
        // history (all statuses, so a repeat after an operator decision is still a no-op).
        Optional<MemoryFact> alreadyProposed = facts.findByEntityIdOrderByValidFromDesc(entityId).stream()
                .filter(fact -> proposalHash.equals(fact.getProposalHash()))
                .findFirst();
        return alreadyProposed.orElseGet(
                () -> insertFact(entityId, factKey, command, actor, origin, status, proposalHash, now));
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

        String after = audit("factKey", factKey, "value", command.valueText(),
                "status", FactStatus.ACTIVE.name());
        assertions.save(assertion(MemorySubjectType.FACT, current.getId(), MemoryAction.SUPERSEDE, actor,
                command.sourceRef(), now, before, after));
        assertions.save(assertion(MemorySubjectType.FACT, fresh.getId(), MemoryAction.CREATE, actor,
                command.sourceRef(), now, null, after));
        return fresh;
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
     * Merge is operator-only: the source keeps a {@code merged_into} redirect and its live
     * facts and relations move to the surviving entity in the same transaction.
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

        for (MemoryFact fact : facts.findByEntityIdOrderByValidFromDesc(sourceId)) {
            fact.setEntityId(targetId);
            facts.save(fact);
        }
        for (FactStatus status : LIVE_RELATION_STATUSES) {
            for (MemoryRelation relation : relations.findByFromEntityIdAndStatus(sourceId, status)) {
                relation.setFromEntityId(targetId);
                relations.save(relation);
            }
            for (MemoryRelation relation : relations.findByToEntityIdAndStatus(sourceId, status)) {
                relation.setToEntityId(targetId);
                relations.save(relation);
            }
        }

        String before = audit("status", source.getStatus().name(), "mergedInto", null);
        source.setStatus(EntityStatus.MERGED);
        source.setMergedInto(targetId);
        entities.save(source);
        assertions.save(assertion(MemorySubjectType.ENTITY, sourceId, MemoryAction.MERGE, actor,
                null, now, before, audit(
                        "status", EntityStatus.MERGED.name(),
                        "mergedInto", targetId.toString())));
        return source;
    }

    // ---------------------------------------------------------------- helpers

    private MemoryFact insertFact(UUID entityId, String factKey, AddFactCommand command, MemoryActor actor,
                                  MemoryOrigin origin, FactStatus status, String proposalHash, Instant now) {
        MemoryFact fact = MemoryFact.builder()
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
        facts.save(fact);
        assertions.save(assertion(MemorySubjectType.FACT, fact.getId(), MemoryAction.CREATE, actor,
                command.sourceRef(), now, null, audit(
                        "entityId", entityId.toString(),
                        "factKey", factKey,
                        "status", status.name(),
                        "origin", origin.name())));
        return fact;
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

    private MemoryEntity requireEntity(UUID entityId) {
        if (entityId == null) {
            throw new MemoryValidationException("entityId is required");
        }
        return entities.findById(entityId)
                .orElseThrow(() -> new MemoryNotFoundException("memory entity " + entityId + " not found"));
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
}
