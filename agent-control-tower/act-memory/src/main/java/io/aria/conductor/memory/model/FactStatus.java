package io.aria.conductor.memory.model;

/**
 * Lifecycle of a fact or relation. EXTRACTION writes land PENDING; only an operator
 * decision (or an OPERATOR/RUN write) makes a row ACTIVE.
 */
public enum FactStatus {
    PENDING, ACTIVE, RETIRED
}
