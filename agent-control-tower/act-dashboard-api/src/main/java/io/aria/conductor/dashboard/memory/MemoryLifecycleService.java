package io.aria.conductor.dashboard.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aria.conductor.memory.dto.MemoryActor;
import io.aria.conductor.memory.model.EntityStatus;
import io.aria.conductor.memory.model.FactStatus;
import io.aria.conductor.memory.model.MemoryAction;
import io.aria.conductor.memory.model.MemoryActorType;
import io.aria.conductor.memory.model.MemoryAssertion;
import io.aria.conductor.memory.model.MemoryEntity;
import io.aria.conductor.memory.model.MemoryFact;
import io.aria.conductor.memory.model.MemorySubjectType;
import io.aria.conductor.memory.repository.MemoryAssertionRepository;
import io.aria.conductor.memory.repository.MemoryEntityRepository;
import io.aria.conductor.memory.repository.MemoryFactRepository;
import io.aria.conductor.memory.service.MemoryConflictException;
import io.aria.conductor.memory.service.MemoryNotFoundException;
import io.aria.conductor.memory.service.MemoryValidationException;
import io.aria.conductor.memory.service.MemoryValueGuard;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The operator lifecycle operations of the REST surface that the merged
 * {@code MemoryCommandService} does not expose yet: entity rename, alias, retire, and fact
 * retire.
 * <p>
 * These methods deliberately reuse the act-memory model, repositories, secret guard, and
 * assertion contract; this is not a second ontology. They stay operator-only, write one
 * assertion per change, and are transactional. When act-memory grows the matching
 * {@code MemoryCommandService} methods, this class should delegate to them and disappear
 * (tracked as a follow-up in the lane handoff).
 */
@Service
public class MemoryLifecycleService {

    private final MemoryEntityRepository entities;
    private final MemoryFactRepository facts;
    private final MemoryAssertionRepository assertions;
    private final MemoryValueGuard guard;
    private final ObjectMapper mapper;

    public MemoryLifecycleService(MemoryEntityRepository entities,
                                  MemoryFactRepository facts,
                                  MemoryAssertionRepository assertions,
                                  MemoryValueGuard guard,
                                  ObjectMapper mapper) {
        this.entities = entities;
        this.facts = facts;
        this.assertions = assertions;
        this.guard = guard;
        this.mapper = mapper;
    }

    /** Operator-only rename: replace the display name, keep the canonical key intact. */
    @Transactional
    public MemoryEntity renameEntity(UUID entityId, String displayName, MemoryActor actor) {
        requireOperator(actor);
        String name = requireText(displayName, "displayName");
        guard.checkValue("displayName", name);
        MemoryEntity entity = requireActiveEntity(entityId);
        String before = entity.getDisplayName();
        entity.setDisplayName(name);
        entities.save(entity);
        flushEntities("renamed");
        assertions.save(assertion(MemorySubjectType.ENTITY, entityId, MemoryAction.UPDATE, actor, null,
                audit("displayName", before), audit("displayName", name)));
        return entity;
    }

    /**
     * Operator-only alias update: merge the given aliases into the entity's
     * {@code attributes} JSON ({@code {"aliases":[...]}}), which is the display metadata the
     * recall mention extractor reads. Existing attributes are preserved.
     */
    @Transactional
    public MemoryEntity addAliases(UUID entityId, List<String> aliases, MemoryActor actor) {
        requireOperator(actor);
        if (aliases == null || aliases.isEmpty()) {
            throw new MemoryValidationException("aliases are required");
        }
        List<String> cleaned = new ArrayList<>();
        for (String alias : aliases) {
            String value = requireText(alias, "alias");
            guard.checkValue("alias", value);
            cleaned.add(value);
        }
        MemoryEntity entity = requireActiveEntity(entityId);
        ObjectNode attributes = attributesOf(entity);
        ArrayNode existing = attributes.withArray("aliases");
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        existing.forEach(node -> {
            if (node.isTextual()) {
                merged.add(node.asText());
            }
        });
        merged.addAll(cleaned);
        ArrayNode replacement = mapper.createArrayNode();
        merged.forEach(replacement::add);
        attributes.set("aliases", replacement);
        String before = entity.getAttributes();
        entity.setAttributes(attributes.toString());
        entities.save(entity);
        flushEntities("alias-updated");
        assertions.save(assertion(MemorySubjectType.ENTITY, entityId, MemoryAction.UPDATE, actor, null,
                audit("aliases", aliasesOf(before)), audit("aliases", List.copyOf(merged))));
        return entity;
    }

    /**
     * Operator-only retire: the entity leaves recall and the ACTIVE listing, and so does its open
     * graph.
     * <p>
     * T42 Item 3: this used to retire the entity row only, unlike a re-proposal
     * ({@code MemoryCommandService.retireLiveGraph}), so a PENDING fact stayed PENDING under a
     * RETIRED entity. The operator's next decision on it was then refused with "which is RETIRED; the
     * entity is no longer active …" — wording that matches neither client marker, so the card kept a
     * Retry that could only ever 409 and the composer stayed gated. The open facts are closed here,
     * with the same shape as the re-proposal sweep: RETIRED, {@code valid_to} stamped, one RETIRE
     * assertion per row, and the proposal hash released (V81: one row per proposal) so the value can
     * be proposed again.
     */
    @Transactional
    public MemoryEntity retireEntity(UUID entityId, MemoryActor actor) {
        requireOperator(actor);
        MemoryEntity entity = requireEntity(entityId);
        if (entity.getStatus() == EntityStatus.RETIRED) {
            return entity;
        }
        if (entity.getStatus() != EntityStatus.ACTIVE) {
            throw new MemoryConflictException("entity " + entityId + " is " + entity.getStatus()
                    + " and cannot be retired");
        }
        int factsRetired = retireOpenFacts(entity, actor);
        entity.setStatus(EntityStatus.RETIRED);
        entities.save(entity);
        flushEntities("retired");
        assertions.save(assertion(MemorySubjectType.ENTITY, entityId, MemoryAction.RETIRE, actor, null,
                audit("status", EntityStatus.ACTIVE.name()),
                audit("status", EntityStatus.RETIRED.name(), "factsRetired", factsRetired)));
        return entity;
    }

    /**
     * Closes the entity's current and proposed facts, mirroring
     * {@code MemoryCommandService.retireLiveGraph}: ACTIVE current values first, then PENDING
     * proposals, each with its own RETIRE assertion. The audit payload names the key and the status
     * it closed, never the value — a fact value can be CONFIDENTIAL.
     *
     * @return the number of fact rows closed, recorded on the entity's RETIRE assertion
     */
    private int retireOpenFacts(MemoryEntity entity, MemoryActor actor) {
        Instant now = Instant.now();
        int closed = 0;
        for (FactStatus status : List.of(FactStatus.ACTIVE, FactStatus.PENDING)) {
            for (MemoryFact fact : facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(
                    entity.getId(), status)) {
                if (fact == null) {
                    continue;
                }
                fact.setStatus(FactStatus.RETIRED);
                fact.setValidTo(now);
                // Closed without approval, so the proposal hash is released (V81: one row per
                // proposal) and the value can be proposed again instead of colliding with this row.
                fact.setProposalHash(null);
                facts.save(fact);
                assertions.save(assertion(MemorySubjectType.FACT, fact.getId(), MemoryAction.RETIRE, actor,
                        null,
                        audit("status", status.name(), "factKey", fact.getFactKey()),
                        audit("status", FactStatus.RETIRED.name(), "factKey", fact.getFactKey(),
                                "reason", "entity retired", "entityId", entity.getId().toString())));
                closed++;
            }
        }
        return closed;
    }

    /**
     * Operator-only fact retire: close the validity window and write an assertion. A
     * PENDING proposal retires the same way as a rejected proposal; an already-retired fact
     * is an idempotent no-op.
     */
    @Transactional
    public MemoryFact retireFact(UUID factId, String sourceRef, MemoryActor actor) {
        requireOperator(actor);
        if (factId == null) {
            throw new MemoryValidationException("factId is required");
        }
        MemoryFact fact = facts.findById(factId)
                .orElseThrow(() -> new MemoryNotFoundException("memory fact " + factId + " not found"));
        if (fact.getStatus() == FactStatus.RETIRED) {
            return fact;
        }
        Instant now = Instant.now();
        String before = audit("status", fact.getStatus().name(), "factKey", fact.getFactKey());
        fact.setStatus(FactStatus.RETIRED);
        fact.setValidTo(now);
        // T42 Item 2's rule at the REST producer: a row closed without approval releases its
        // proposal hash (V81: one row per proposal), so the identical value can be proposed again.
        fact.setProposalHash(null);
        facts.save(fact);
        flushFacts("retired");
        assertions.save(assertion(MemorySubjectType.FACT, factId, MemoryAction.RETIRE, actor, sourceRef,
                before, audit("status", FactStatus.RETIRED.name(), "factKey", fact.getFactKey())));
        return fact;
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Pushes the write through the row-level {@code @Version} inside the transaction,
     * mirroring {@code MemoryCommandService}: a concurrent change surfaces here as the
     * governed conflict with a fixed message instead of at commit as a raw
     * {@code ObjectOptimisticLockingFailureException} whose Hibernate text names the entity
     * class and row id.
     */
    private void flushEntities(String operation) {
        try {
            entities.flush();
        } catch (OptimisticLockingFailureException e) {
            throw new MemoryConflictException("the entity changed while it was being " + operation
                    + "; re-read before retrying");
        }
    }

    private void flushFacts(String operation) {
        try {
            facts.flush();
        } catch (OptimisticLockingFailureException e) {
            throw new MemoryConflictException("the fact changed while it was being " + operation
                    + "; re-read before retrying");
        }
    }

    private MemoryEntity requireEntity(UUID entityId) {
        if (entityId == null) {
            throw new MemoryValidationException("entityId is required");
        }
        return entities.findById(entityId)
                .orElseThrow(() -> new MemoryNotFoundException("memory entity " + entityId + " not found"));
    }

    private MemoryEntity requireActiveEntity(UUID entityId) {
        MemoryEntity entity = requireEntity(entityId);
        if (entity.getStatus() != EntityStatus.ACTIVE) {
            throw new MemoryConflictException("entity " + entityId + " is " + entity.getStatus()
                    + "; only an ACTIVE entity can be changed");
        }
        return entity;
    }

    private ObjectNode attributesOf(MemoryEntity entity) {
        String attributes = entity.getAttributes();
        if (attributes == null || attributes.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode parsed = mapper.readTree(attributes);
            if (parsed instanceof ObjectNode object) {
                return object;
            }
        } catch (Exception ignored) {
            // A malformed legacy value is replaced by a clean object instead of failing the
            // operator's alias update; unknown keys are not recoverable, so we start over.
        }
        return mapper.createObjectNode();
    }

    private List<String> aliasesOf(String attributes) {
        if (attributes == null || attributes.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = mapper.readTree(attributes);
            JsonNode list = root.get("aliases");
            if (list == null || !list.isArray()) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            list.forEach(node -> {
                if (node.isTextual()) {
                    values.add(node.asText());
                }
            });
            return values;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static void requireOperator(MemoryActor actor) {
        if (actor == null || actor.type() != MemoryActorType.OPERATOR) {
            throw new SecurityException("memory lifecycle changes are operator-only");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new MemoryValidationException(field + " is required");
        }
        return value.trim();
    }

    private static MemoryAssertion assertion(MemorySubjectType subjectType, UUID subjectId,
                                             MemoryAction action, MemoryActor actor, String sourceRef,
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
                .createdAt(Instant.now())
                .build();
    }

    private String audit(Object... keysAndValues) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            snapshot.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        try {
            return mapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            return null;
        }
    }
}
