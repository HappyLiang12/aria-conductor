package io.aria.conductor.dashboard.dto;

/**
 * The dashboard summary shape returned by {@code GET /api/v1/dashboard/summary}
 * and the MCP {@code get_dashboard_summary} tool. The two paths share this shape
 * but are not byte-identical: the REST controller counts {@code RUNNING} and
 * {@code WAITING_INPUT} runs as {@code runningRuns}, while the MCP tool counts
 * {@code RUNNING} only (a pre-existing divergence in the owning lane).
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
