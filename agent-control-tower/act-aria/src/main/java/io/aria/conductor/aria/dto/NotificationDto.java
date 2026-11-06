package io.aria.conductor.aria.dto;

import java.time.Instant;

public record NotificationDto(
        String id,
        String type,
        String title,
        String body,
        String resourceType,
        String resourceId,
        String jobId,
        boolean isRead,
        Instant createdAt) {
}
