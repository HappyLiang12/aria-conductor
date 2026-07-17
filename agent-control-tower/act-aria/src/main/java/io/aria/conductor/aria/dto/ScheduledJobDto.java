package io.aria.conductor.aria.dto;

import java.time.Instant;

public record ScheduledJobDto(
        String id,
        String scheduleType,
        String category,
        String title,
        String scheduleExpression,
        String notificationTitle,
        Instant nextFireAt,
        Instant lastFiredAt,
        String notificationBody,
        String status,
        Instant createdAt,
        Instant updatedAt) {
}
