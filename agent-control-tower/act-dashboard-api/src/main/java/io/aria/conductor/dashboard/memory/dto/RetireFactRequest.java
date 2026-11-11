package io.aria.conductor.dashboard.memory.dto;

/** Optional body of {@code POST /api/v1/memory/facts/{id}/retire}. */
public record RetireFactRequest(String sourceRef) {
}
