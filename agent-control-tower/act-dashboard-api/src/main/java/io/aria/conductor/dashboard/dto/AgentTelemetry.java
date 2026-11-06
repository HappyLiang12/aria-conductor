package io.aria.conductor.dashboard.dto;

import java.util.UUID;

public record AgentTelemetry(
        UUID agentId,
        long totalTokensToday,
        long callCountToday
) {}
