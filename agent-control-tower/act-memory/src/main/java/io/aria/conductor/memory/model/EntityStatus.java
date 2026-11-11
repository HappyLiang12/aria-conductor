package io.aria.conductor.memory.model;

/**
 * Lifecycle of a memory entity. A merged entity stays readable as a redirect
 * ({@code mergedInto}) instead of disappearing.
 * <p>
 * {@code PENDING} is an extraction's proposal for an entity nobody has created yet: it is the
 * parent of the proposal's PENDING facts and becomes ACTIVE (promoting the batch) or RETIRED
 * (retiring the batch) only through an operator decision. A PENDING entity is never memory: the
 * recall, prompt, default-list and detail read paths exclude it (spec §5.5, decision C3).
 */
public enum EntityStatus {
    PENDING, ACTIVE, MERGED, RETIRED
}
