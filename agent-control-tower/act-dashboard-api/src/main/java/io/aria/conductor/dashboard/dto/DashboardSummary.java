package io.aria.conductor.dashboard.dto;

/**
 * The dashboard summary shape returned by {@code GET /api/v1/dashboard/summary}
 * and the MCP {@code get_dashboard_summary} tool. Both paths share this shape
 * and count {@code RUNNING} plus {@code WAITING_INPUT} runs as
 * {@code runningRuns}, and only blocking asks as {@code pendingApprovals}.
 *
 * @param activeAgents      the live fleet the KPI headlines: {@code HEALTHY + DEGRADED}.
 *                          Degraded agents are active work the dashboard details as
 *                          "Healthy · N degraded"; UNHEALTHY agents are not counted here
 *                          (they are surfaced on the crew page, which lists every
 *                          non-retired agent)
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
