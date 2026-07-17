package io.aria.conductor.dashboard.dto;

public record DashboardSummary(
        long activeAgents,
        long runningRuns,
        long pendingApprovals,
        long totalTokensBurned
) {}