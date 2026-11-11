package io.aria.conductor.memory.dto;

import java.util.List;

/**
 * The ranked recall slice a prompt may see (spec §8.4 step 5): at most 12 entities and 40
 * facts, already filtered by the sensitivity ceiling. {@code truncated} records that a cap
 * was hit so the assembler can mark the section. Empty lists mean "nothing matched", never
 * "recall failed" — a failure is the contributor's degraded reason, not a result.
 */
public record RecallResult(List<MemoryEntityView> entities, List<MemoryFactView> facts, boolean truncated) {

    public RecallResult {
        entities = entities == null ? List.of() : List.copyOf(entities);
        facts = facts == null ? List.of() : List.copyOf(facts);
    }

    public static RecallResult empty() {
        return new RecallResult(List.of(), List.of(), false);
    }

    public boolean isEmpty() {
        return entities.isEmpty() && facts.isEmpty();
    }
}
