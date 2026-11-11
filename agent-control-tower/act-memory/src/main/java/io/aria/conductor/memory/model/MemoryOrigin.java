package io.aria.conductor.memory.model;

/**
 * Who wrote a memory row. Source authority ranks OPERATOR over RUN over EXTRACTION
 * during recall, and the governance matrix is keyed on it.
 */
public enum MemoryOrigin {
    OPERATOR, RUN, EXTRACTION
}
