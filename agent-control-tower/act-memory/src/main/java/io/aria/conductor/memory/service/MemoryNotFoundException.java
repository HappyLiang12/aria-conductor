package io.aria.conductor.memory.service;

/** A referenced memory subject does not exist. Mapped to 404 by the REST layer. */
public class MemoryNotFoundException extends RuntimeException {

    public MemoryNotFoundException(String message) {
        super(message);
    }
}
