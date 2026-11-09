package io.aria.conductor.memory.service;

import io.aria.conductor.memory.dto.MemoryEntityView;
import io.aria.conductor.memory.dto.MemoryFactView;
import io.aria.conductor.memory.model.EntityStatus;
import io.aria.conductor.memory.model.FactStatus;
import io.aria.conductor.memory.model.MemoryEntity;
import io.aria.conductor.memory.model.MemoryFact;
import io.aria.conductor.memory.repository.MemoryEntityRepository;
import io.aria.conductor.memory.repository.MemoryFactRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The deterministic read model behind the memory REST and MCP surfaces: entity list and
 * detail, the current ACTIVE facts of one entity, an exact fact-key lookup, and the whole
 * validity history for an operator view.
 * <p>
 * Only ACTIVE, still-open facts are "current": a PENDING extraction proposal is review
 * material and a closed row is history (spec §11). There is no model call and no ranking
 * here; retrieval ranking lives in {@link MemoryRecallService}.
 */
@Service
public class MemoryQueryService {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private final MemoryEntityRepository entities;
    private final MemoryFactRepository facts;

    public MemoryQueryService(MemoryEntityRepository entities, MemoryFactRepository facts) {
        this.entities = entities;
        this.facts = facts;
    }

    public MemoryEntityView getEntity(UUID entityId) {
        UUID id = requireId(entityId);
        return entities.findById(id)
                .map(MemoryEntityView::from)
                .orElseThrow(() -> new MemoryNotFoundException("memory entity " + id + " not found"));
    }

    /**
     * One page of entities, ACTIVE by default, deterministic by (kind, canonical key, id).
     * A kind uses the indexed kind/status query; an open listing reads all rows and filters
     * the lifecycle status, because the frozen repository has no status-only query.
     */
    @Transactional(readOnly = true)
    public List<MemoryEntityView> listEntities(String kind, EntityStatus status, int limit) {
        EntityStatus target = status == null ? EntityStatus.ACTIVE : status;
        List<MemoryEntity> rows = kind == null || kind.isBlank()
                ? safe(entities.findAll())
                : safe(entities.findByKindAndStatus(kind.trim(), target));
        int pageSize = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        return rows.stream()
                .filter(Objects::nonNull)
                .filter(entity -> entity.getStatus() == target)
                .sorted(Comparator.comparing((MemoryEntity entity) -> nullSafe(entity.getKind()))
                        .thenComparing(entity -> nullSafe(entity.getCanonicalKey()))
                        .thenComparing(entity -> nullSafe(entity.getId())))
                .limit(pageSize)
                .map(MemoryEntityView::from)
                .toList();
    }

    /** The current ACTIVE values of an entity, newest first. */
    public List<MemoryFactView> currentFacts(UUID entityId) {
        UUID id = requireId(entityId);
        return safe(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(id, FactStatus.ACTIVE))
                .stream()
                .filter(Objects::nonNull)
                .map(MemoryFactView::from)
                .toList();
    }

    /** Exact fact-key match against the current ACTIVE values; the key is not a pattern. */
    public Optional<MemoryFactView> currentFact(UUID entityId, String factKey) {
        if (factKey == null || factKey.isBlank()) {
            return Optional.empty();
        }
        return currentFacts(entityId).stream()
                .filter(view -> factKey.equals(view.factKey()))
                .findFirst();
    }

    /** Every value ever recorded for the entity, newest {@code valid_from} first. */
    public List<MemoryFactView> factHistory(UUID entityId) {
        UUID id = requireId(entityId);
        return safe(facts.findByEntityIdOrderByValidFromDesc(id))
                .stream()
                .filter(Objects::nonNull)
                .map(MemoryFactView::from)
                .toList();
    }

    private static UUID requireId(UUID entityId) {
        if (entityId == null) {
            throw new MemoryValidationException("entityId is required");
        }
        return entityId;
    }

    private static String nullSafe(Object value) {
        return value == null ? "" : value.toString();
    }

    private static <T> List<T> safe(List<T> rows) {
        return rows == null ? List.of() : rows;
    }
}
