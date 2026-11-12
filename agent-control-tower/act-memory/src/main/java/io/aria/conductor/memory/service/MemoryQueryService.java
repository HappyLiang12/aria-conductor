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
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The deterministic read model behind the memory REST and MCP surfaces: entity list and
 * detail, the current ACTIVE facts of one entity, an exact fact-key lookup, and the whole
 * validity history for an operator view.
 * <p>
 * Only ACTIVE, still-open facts are "current": a PENDING extraction proposal is review
 * material and a closed row is history (spec §11). Entities follow the same rule by default —
 * {@link #listEntities} lists ACTIVE unless the operator asks for another status explicitly,
 * and {@link #getEntity} refuses a PENDING entity — so an unapproved proposal never reaches a
 * prompt or an agent-facing read. There is no model call and no ranking here; retrieval ranking
 * lives in {@link MemoryRecallService}.
 */
@Service
public class MemoryQueryService {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    /**
     * The documented entity-kind vocabulary ({@link MemoryEntity}: "Open vocabulary: PERSON,
     * PROJECT, REPO, MACHINE, AGENT, TASK, ORG"). The stored form is the uppercase name; the
     * filter accepts any casing of one of these and refuses anything else.
     */
    static final Set<String> KNOWN_KINDS = Set.of(
            "PERSON", "PROJECT", "REPO", "MACHINE", "AGENT", "TASK", "ORG");

    private final MemoryEntityRepository entities;
    private final MemoryFactRepository facts;

    public MemoryQueryService(MemoryEntityRepository entities, MemoryFactRepository facts) {
        this.entities = entities;
        this.facts = facts;
    }

    /**
     * One entity by id, with the proposal guard: a PENDING entity is review material, so this
     * detail read (REST {@code GET /entities/{id}} and MCP {@code get_memory_entity}) reports it as
     * not found instead of returning it. The operator-facing visibility is the explicit
     * {@code status=PENDING} list and the proposal inbox, which is where the decision is addressed —
     * a read path that cannot prove PENDING exclusion fails closed (spec §5.5, §9).
     */
    public MemoryEntityView getEntity(UUID entityId) {
        return getEntity(entityId, false);
    }

    /**
     * The detail read with the operator's explicit {@code status=PENDING} filter (spec :309-313):
     * with {@code includePending} the reviewer can open the proposal they are deciding. It is a
     * separate flag so the default read above stays fail-closed, and the caller of this variant —
     * the REST detail route — resolves operator authority before it asks, because a PENDING row
     * is review material, never an agent-facing read.
     */
    public MemoryEntityView getEntity(UUID entityId, boolean includePending) {
        UUID id = requireId(entityId);
        return entities.findById(id)
                .filter(entity -> includePending || entity.getStatus() != EntityStatus.PENDING)
                .map(MemoryEntityView::from)
                .orElseThrow(() -> new MemoryNotFoundException("memory entity " + id + " not found"));
    }

    /**
     * One page of entities, ACTIVE by default, deterministic by (kind, canonical key, id).
     * A kind uses the indexed kind/status query; an open listing reads all rows and filters
     * the lifecycle status, because the frozen repository has no status-only query.
     * <p>
     * T53 F-B2 (live bar): the kind filter is normalised to the stored uppercase vocabulary
     * before it reaches the exact-match query. The prompt teaches {@code person / person:happy}
     * while the stored kind is {@code PERSON}; before this, {@code kind=person} matched nothing
     * and answered an empty page, which the model read as "no person entity exists". A known kind
     * with no matching rows is still an empty page, but a kind outside {@link #KNOWN_KINDS} is
     * refused as a VALIDATION error, so an empty page can never be a masked typo. Every read path
     * that takes a kind (MCP {@code list_memory_entities} and REST {@code GET /entities}) funnels
     * through here, so they cannot disagree.
     */
    @Transactional(readOnly = true)
    public List<MemoryEntityView> listEntities(String kind, EntityStatus status, int limit) {
        EntityStatus target = status == null ? EntityStatus.ACTIVE : status;
        String normalizedKind = normalizeKind(kind);
        List<MemoryEntity> rows = normalizedKind == null
                ? safe(entities.findAll())
                : safe(entities.findByKindAndStatus(normalizedKind, target));
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

    /**
     * Blank means "no filter" ({@code null}); otherwise the kind is trimmed and uppercased to the
     * stored vocabulary. An unknown kind is refused instead of being sent to the exact-match query,
     * because an unmatched filter and an empty store produced the same {@code data: []} answer —
     * the live incident where Aria proposed a duplicate identity for the operator.
     */
    private static String normalizeKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return null;
        }
        String trimmed = kind.trim();
        String normalized = trimmed.toUpperCase(Locale.ROOT);
        if (!KNOWN_KINDS.contains(normalized)) {
            throw new MemoryValidationException("Unknown memory entity kind '" + trimmed
                    + "'. Entity kinds are case-insensitive: the accepted kinds are "
                    + String.join(", ", KNOWN_KINDS.stream().sorted().toList())
                    + ". Retry with one of these or omit kind to list every entity.");
        }
        return normalized;
    }

    private static String nullSafe(Object value) {
        return value == null ? "" : value.toString();
    }

    private static <T> List<T> safe(List<T> rows) {
        return rows == null ? List.of() : rows;
    }
}
