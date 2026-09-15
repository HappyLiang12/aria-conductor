package io.aria.conductor.mcp.tools;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.dashboard.dto.DashboardSummary;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.PromptCallRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * Dashboard summary tool. DashboardController computes the REST summary from
 * repositories directly (there is no dashboard service), so this wrapper injects
 * the same repositories and returns the same DashboardSummary DTO — MCP and REST
 * stay at parity on field names and values.
 */
@Component
@RequiredArgsConstructor
public class DashboardTools implements McpTool {

    private final AgentRepository agentRepository;
    private final RunRepository runRepository;
    private final ApprovalRepository approvalRepository;
    private final PromptCallRepository promptCallRepository;
    private final McpProperties mcpProperties;

    @Tool(name = "get_dashboard_summary",
            description = "Get the dashboard summary: active/healthy/degraded agents, running runs, pending approvals and total tokens burned. Same payload as GET /api/v1/dashboard/summary.")
    public String getDashboardSummary() {
        try {
            long healthyAgents = agentRepository.countByHealthStatus(HealthStatus.HEALTHY);
            long degradedAgents = agentRepository.countByHealthStatus(HealthStatus.DEGRADED);
            long runningRuns = runRepository.countByStatus(RunStatus.RUNNING);
            long pendingApprovals = approvalRepository.findByStatus(ApprovalStatus.PENDING).size();
            long totalTokensBurned = promptCallRepository.findAll()
                    .stream()
                    .mapToLong(p -> p.getInputTokens() + p.getOutputTokens())
                    .sum();

            return ToolResponses.ok(new DashboardSummary(healthyAgents, healthyAgents, degradedAgents,
                    runningRuns, pendingApprovals, totalTokensBurned));
        } catch (Exception e) {
            return ToolResponses.error("DASHBOARD_SUMMARY_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }
}
