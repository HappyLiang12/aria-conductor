package io.aria.conductor.dashboard.dto;

import java.time.Instant;

public record ActivityEvent(
        String eventType,
        String resourceType,
        String resourceId,
        String action,
        Instant timestamp,
        String conversationId,
        String details
) {}