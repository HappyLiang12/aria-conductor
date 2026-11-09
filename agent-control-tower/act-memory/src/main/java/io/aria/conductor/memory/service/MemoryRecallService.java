package io.aria.conductor.memory.service;

import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.memory.dto.MemoryEntityView;
import io.aria.conductor.memory.dto.MemoryFactView;
import io.aria.conductor.memory.dto.RecallQuery;
import io.aria.conductor.memory.dto.RecallResult;
import io.aria.conductor.memory.model.EntityStatus;
import io.aria.conductor.memory.model.FactStatus;
import io.aria.conductor.memory.model.MemoryEntity;
import io.aria.conductor.memory.model.MemoryFact;
import io.aria.conductor.memory.model.MemoryOrigin;
import io.aria.conductor.memory.model.MemoryRelation;
import io.aria.conductor.memory.repository.MemoryEntityRepository;
import io.aria.conductor.memory.repository.MemoryFactRepository;
import io.aria.conductor.memory.repository.MemoryRelationRepository;
import io.aria.conductor.memory.service.MemoryMentionExtractor.MentionSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic recall (spec §8.4): lexical mention extraction, one hop of ACTIVE
 * relations, currently-valid ACTIVE facts below the sensitivity ceiling, and a total
 * order over (mention source, distance, source authority, recency, confidence, id).
 * There are no embeddings and no model call here, so the same state and the same query
 * always produce the same list.
 * <p>
 * Ranking, filtering and capping all happen in this class even though the repository
 * queries already filter: a query that ever returns a PENDING, closed, future, or
 * over-ceiling row must still not reach a prompt.
 * <p>
 * Every read is bounded: the exact-key lookup is an {@code IN} over the extracted keys,
 * the display-name/alias candidate set is one page of ACTIVE entities (newest first), the
 * one-hop neighbours are one {@code IN} load rather than one {@code findById} per
 * neighbour, and the facts are one {@code IN} load. Nothing here scans a whole table.
 */
@Service
public class MemoryRecallService {

    static final int MAX_ENTITIES = 12;
    static final int MAX_FACTS = 40;

    /**
     * Size of the bounded ACTIVE page recall matches display names and aliases against.
     * Explicit aliases stay searchable through the extractor for this many newest entities;
     * older rows remain reachable through their canonical key.
     */
    static final int LEXICAL_UNIVERSE_LIMIT = 200;

    private final MemoryEntityRepository entities;
    private final MemoryFactRepository facts;
    private final MemoryRelationRepository relations;
    private final MemoryMentionExtractor extractor;

    public MemoryRecallService(MemoryEntityRepository entities,
                               MemoryFactRepository facts,
                               MemoryRelationRepository relations,
                               MemoryMentionExtractor extractor) {
        this.entities = entities;
        this.facts = facts;
        this.relations = relations;
        this.extractor = extractor;
    }

    public RecallResult recall(RecallQuery query) {
        RecallQuery request = query == null ? new RecallQuery("", null, null) : query;
        Set<String> candidateKeys = extractor.candidateKeys(request.text(), request.focusRef());
        if (candidateKeys.isEmpty()) {
            return RecallResult.empty();
        }
        Sensitivity ceiling = request.maxSensitivity();
        Instant now = Instant.now();

        Map<UUID, Ranked> matched = new LinkedHashMap<>();
        // The exact query is authoritative: the repository matched the canonical key, so
        // the row is a canonical-key mention even if the text also matches elsewhere.
        for (MemoryEntity entity : safe(entities.findByCanonicalKeyInAndStatus(candidateKeys, EntityStatus.ACTIVE))) {
            if (eligible(entity, ceiling)) {
                matched.putIfAbsent(entity.getId(),
                        new Ranked(entity, MentionSource.CANONICAL_KEY.rank(), 0));
            }
        }
        // Display names and aliases have no indexed lookup, so recall matches them against
        // one bounded page of ACTIVE entities instead of the whole table.
        List<MemoryEntity> universe = safe(entities.findByStatusOrderByUpdatedAtDesc(
                EntityStatus.ACTIVE, PageRequest.of(0, LEXICAL_UNIVERSE_LIMIT)));
        Map<UUID, MentionSource> lexical = extractor.match(request.text(), request.focusRef(), universe);
        for (MemoryEntity entity : universe) {
            MentionSource source = lexical.get(entity.getId());
            if (source != null && eligible(entity, ceiling)) {
                matched.merge(entity.getId(), new Ranked(entity, source.rank(), 0),
                        (left, right) -> left.mentionRank() <= right.mentionRank() ? left : right);
            }
        }

        List<Ranked> ordered = new ArrayList<>(matched.values());
        ordered.sort(entityOrder());
        boolean truncated = ordered.size() > MAX_ENTITIES;
        List<Ranked> kept = new ArrayList<>(ordered.subList(0, Math.min(ordered.size(), MAX_ENTITIES)));

        List<Ranked> withNeighbours = expandOneHop(kept, now, ceiling);
        truncated |= withNeighbours.size() > MAX_ENTITIES;
        List<Ranked> finalEntities = new ArrayList<>(
                withNeighbours.subList(0, Math.min(withNeighbours.size(), MAX_ENTITIES)));

        Map<UUID, Ranked> index = new LinkedHashMap<>();
        for (Ranked ranked : finalEntities) {
            index.put(ranked.entity().getId(), ranked);
        }
        List<MemoryFact> current = new ArrayList<>();
        if (!index.isEmpty()) {
            for (MemoryFact fact : safe(facts.findByEntityIdInAndStatusAndValidFromLessThanEqualAndValidToIsNull(
                    List.copyOf(index.keySet()), FactStatus.ACTIVE, now))) {
                if (eligible(fact, index, ceiling, now)) {
                    current.add(fact);
                }
            }
        }
        current.sort(factOrder(index));
        truncated |= current.size() > MAX_FACTS;

        List<MemoryFactView> factViews = current.subList(0, Math.min(current.size(), MAX_FACTS)).stream()
                .map(MemoryFactView::from)
                .toList();
        List<MemoryEntityView> entityViews = finalEntities.stream()
                .map(ranked -> MemoryEntityView.from(ranked.entity()))
                .toList();
        return new RecallResult(entityViews, factViews, truncated);
    }

    /**
     * One hop only: the neighbours of the ranked direct matches are added, never the
     * neighbours of those neighbours. Relations are geometry; validity and the ceiling are
     * enforced here, and all neighbour ids are loaded in one query.
     */
    private List<Ranked> expandOneHop(List<Ranked> kept, Instant now, Sensitivity ceiling) {
        List<Ranked> direct = new ArrayList<>(kept);
        direct.sort(Comparator.comparing(ranked -> idString(ranked.entity().getId())));
        Set<UUID> known = new LinkedHashSet<>();
        for (Ranked ranked : kept) {
            known.add(ranked.entity().getId());
        }
        List<UUID> neighbourIds = new ArrayList<>();
        for (Ranked origin : direct) {
            for (MemoryRelation relation : liveRelations(origin.entity().getId(), now, ceiling)) {
                UUID other = origin.entity().getId().equals(relation.getFromEntityId())
                        ? relation.getToEntityId()
                        : relation.getFromEntityId();
                if (other != null && known.add(other)) {
                    neighbourIds.add(other);
                }
            }
        }
        List<Ranked> neighbours = new ArrayList<>();
        if (!neighbourIds.isEmpty()) {
            Map<UUID, MemoryEntity> loaded = new LinkedHashMap<>();
            for (MemoryEntity neighbour : safe(entities.findByIdInAndStatus(neighbourIds, EntityStatus.ACTIVE))) {
                if (neighbour != null && neighbour.getId() != null) {
                    loaded.put(neighbour.getId(), neighbour);
                }
            }
            for (UUID id : neighbourIds) {
                MemoryEntity neighbour = loaded.get(id);
                if (eligible(neighbour, ceiling)) {
                    neighbours.add(new Ranked(neighbour, MentionSource.NEIGHBOUR.rank(), 1));
                }
            }
        }
        neighbours.sort(entityOrder());
        List<Ranked> combined = new ArrayList<>(kept);
        combined.addAll(neighbours);
        return combined;
    }

    private List<MemoryRelation> liveRelations(UUID entityId, Instant now, Sensitivity ceiling) {
        List<MemoryRelation> live = new ArrayList<>();
        for (MemoryRelation relation : safe(relations.findByFromEntityIdAndStatus(entityId, FactStatus.ACTIVE))) {
            if (isLive(relation, now, ceiling)) {
                live.add(relation);
            }
        }
        for (MemoryRelation relation : safe(relations.findByToEntityIdAndStatus(entityId, FactStatus.ACTIVE))) {
            if (isLive(relation, now, ceiling)) {
                live.add(relation);
            }
        }
        live.sort(Comparator.comparing(relation -> idString(relation.getId())));
        return live;
    }

    private static boolean isLive(MemoryRelation relation, Instant now, Sensitivity ceiling) {
        if (relation == null || relation.getStatus() != FactStatus.ACTIVE || relation.getValidTo() != null) {
            return false;
        }
        if (relation.getFromEntityId() == null || relation.getFromEntityId().equals(relation.getToEntityId())) {
            return false;
        }
        if (relation.getValidFrom() != null && relation.getValidFrom().isAfter(now)) {
            return false;
        }
        return belowCeiling(relation.getSensitivity(), ceiling);
    }

    private static boolean eligible(MemoryEntity entity, Sensitivity ceiling) {
        return entity != null
                && entity.getId() != null
                && entity.getStatus() == EntityStatus.ACTIVE
                && belowCeiling(entity.getSensitivity(), ceiling);
    }

    private static boolean eligible(MemoryFact fact, Map<UUID, Ranked> index, Sensitivity ceiling, Instant now) {
        return fact != null
                && fact.getId() != null
                && fact.getEntityId() != null
                && index.containsKey(fact.getEntityId())
                && fact.getStatus() == FactStatus.ACTIVE
                && fact.getValidTo() == null
                && (fact.getValidFrom() == null || !fact.getValidFrom().isAfter(now))
                && belowCeiling(fact.getSensitivity(), ceiling);
    }

    /**
     * Fail closed: a row with no sensitivity is treated as {@link Sensitivity#RESTRICTED},
     * so a missing label can never widen what a provider is allowed to see.
     */
    private static boolean belowCeiling(Sensitivity sensitivity, Sensitivity ceiling) {
        Sensitivity effective = sensitivity == null ? Sensitivity.RESTRICTED : sensitivity;
        Sensitivity limit = ceiling == null ? Sensitivity.INTERNAL : ceiling;
        return effective.ordinal() <= limit.ordinal();
    }

    private static Comparator<Ranked> entityOrder() {
        return Comparator.comparingInt(Ranked::mentionRank)
                .thenComparingInt(Ranked::distance)
                .thenComparingInt(ranked -> authority(ranked.entity().getOrigin()))
                .thenComparing(ranked -> ranked.entity().getUpdatedAt(),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ranked -> idString(ranked.entity().getId()));
    }

    private static Comparator<MemoryFact> factOrder(Map<UUID, Ranked> index) {
        return Comparator.comparingInt((MemoryFact fact) -> index.get(fact.getEntityId()).mentionRank())
                .thenComparingInt(fact -> index.get(fact.getEntityId()).distance())
                .thenComparingInt(fact -> authority(fact.getOrigin()))
                .thenComparing((MemoryFact fact) -> fact.getUpdatedAt(),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing((MemoryFact fact) -> fact.getConfidence(),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(fact -> idString(fact.getId()));
    }

    /** OPERATOR outranks RUN outranks EXTRACTION (spec §8.4 step 4). */
    private static int authority(MemoryOrigin origin) {
        return origin == null ? MemoryOrigin.values().length : origin.ordinal();
    }

    private static String idString(UUID id) {
        return id == null ? "" : id.toString();
    }

    private static <T> List<T> safe(List<T> rows) {
        return rows == null ? List.of() : rows;
    }

    private record Ranked(MemoryEntity entity, int mentionRank, int distance) {
    }
}
