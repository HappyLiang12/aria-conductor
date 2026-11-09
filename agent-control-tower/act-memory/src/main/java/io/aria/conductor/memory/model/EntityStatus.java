package io.aria.conductor.memory.model;

/**
 * Lifecycle of a memory entity. A merged entity stays readable as a redirect
 * ({@code mergedInto}) instead of disappearing.
 */
public enum EntityStatus {
    ACTIVE, MERGED, RETIRED
}
