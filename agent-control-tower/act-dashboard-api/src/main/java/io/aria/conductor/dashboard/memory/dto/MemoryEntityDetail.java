package io.aria.conductor.dashboard.memory.dto;

import io.aria.conductor.memory.dto.MemoryEntityView;
import io.aria.conductor.memory.dto.MemoryFactView;

import java.util.List;

/**
 * Entity detail (spec §9): the entity, its facts (with validity windows), its current
 * relations, its knowledge links, and the entity's assertion timeline. Ids, provenance,
 * sensitivity and validity travel on every nested view.
 */
public record MemoryEntityDetail(
        MemoryEntityView entity,
        List<MemoryFactView> facts,
        List<MemoryRelationView> relations,
        List<MemoryKnowledgeLinkView> links,
        List<MemoryAssertionView> assertions) {
}
