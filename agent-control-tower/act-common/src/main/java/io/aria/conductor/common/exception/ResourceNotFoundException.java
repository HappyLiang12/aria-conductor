package io.aria.conductor.common.exception;

public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resourceType, Object id) {
        super(String.format("%s not found with id: %s", resourceType, id));
    }

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
