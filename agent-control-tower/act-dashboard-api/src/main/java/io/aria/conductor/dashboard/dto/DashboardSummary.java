package io.aria.conductor.dashboard.dto;

/**
 * The dashboard summary, shared byte-for-byte by {@code GET /api/v1/dashboard/summary}
 * and the MCP {@code get_dashboard_summary} tool.
 *
 * @param totalTokensBurned the sum over runs that reported usage (Task 7 / spec 5.4),
 *                          never a fabricated zero: a run that reported nothing
 *                          contributes nothing
 * @param runsWithoutUsage  how many runs reported no usable measurement, so a zero
 *                          {@code totalTokensBurned} is distinguishable from a zero cost
 */
public record DashboardSummary(
        long activeAgents,
        long healthyAgents,
        long degradedAgents,
        long runningRuns,
        long pendingApprovals,
        long totalTokensBurned,
        long runsWithoutUsage
) {}
