package io.aria.conductor.memory.service;

/**
 * The write collides with the current state: an active value already exists, or the
 * caller's version is stale. Mapped to 409 by the REST layer.
 */
public class MemoryConflictException extends RuntimeException {

    public MemoryConflictException(String message) {
        super(message);
    }
}
