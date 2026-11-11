package io.aria.conductor.memory.model;

/**
 * Lifecycle of a knowledge link. Backfill and extraction propose; only an operator
 * decision promotes a link to ACTIVE.
 */
public enum MemoryLinkStatus {
    PROPOSED, ACTIVE, RETIRED
}
