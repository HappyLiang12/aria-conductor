package io.aria.conductor.dashboard.dto;

public record DashboardSummary(
        long activeAgents,
        long healthyAgents,
        long degradedAgents,
        long runningRuns,
        long pendingApprovals,
        long totalTokensBurned
) {}