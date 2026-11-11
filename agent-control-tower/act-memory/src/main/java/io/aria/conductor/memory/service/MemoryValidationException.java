package io.aria.conductor.memory.service;

/**
 * The command is not allowed by the governance matrix or fails field validation.
 * Messages name the field (never a secret-looking value). Mapped to 422 by the REST layer.
 */
public class MemoryValidationException extends RuntimeException {

    public MemoryValidationException(String message) {
        super(message);
    }
}
