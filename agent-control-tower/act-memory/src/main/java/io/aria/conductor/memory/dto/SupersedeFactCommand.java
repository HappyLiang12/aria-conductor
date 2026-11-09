package io.aria.conductor.memory.dto;

/**
 * Replace the current value of a fact. {@code expectedVersion} is the version the caller
 * read; a mismatch is a conflict, never a silent overwrite.
 */
public record SupersedeFactCommand(
        String valueText,
        long expectedVersion,
        String sourceRef) {
}
