package io.aria.conductor.aria.tools.handlers;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.tool.ToolHandler;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component("dashboardToolHandler")
public class DashboardToolHandler implements ToolHandler {

    private final ApprovalRepository approvalRepository;
    private final AgentRepository agentRepository;
    private final RunRepository runRepository;
    private final KnowledgeItemRepository knowledgeItemRepository;

    public DashboardToolHandler(ApprovalRepository approvalRepository,
                                AgentRepository agentRepository,
                                RunRepository runRepository,
                                KnowledgeItemRepository knowledgeItemRepository) {
        this.approvalRepository = approvalRepository;
        this.agentRepository = agentRepository;
        this.runRepository = runRepository;
        this.knowledgeItemRepository = knowledgeItemRepository;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String toolName = Objects.toString(arguments.get("toolName"), "");
        try {
            return switch (toolName) {
                case "get_dashboard_summary" -> getDashboardSummary();
                default -> error("Unknown tool: " + toolName);
            };
        } catch (Exception e) {
            log.error("DashboardToolHandler failed for {}", toolName, e);
            return error(e.getMessage());
        }
    }

    private String getDashboardSummary() {
        long totalAgents = agentRepository.count();
        long healthyAgents = agentRepository.countByHealthStatus(HealthStatus.HEALTHY);
        long totalRuns = runRepository.count();
        long runningRuns = runRepository.countByStatus(RunStatus.RUNNING);
        long pendingApprovals = approvalRepository.findByStatus(
                io.aria.conductor.common.model.ApprovalStatus.PENDING).size();
        long knowledgeItems = knowledgeItemRepository.count();

        StringBuilder sb = new StringBuilder("Dashboard Summary:\n");
        sb.append("  Total Agents: ").append(totalAgents).append("\n");
        sb.append("  Healthy Agents: ").append(healthyAgents).append("\n");
        sb.append("  Total Runs: ").append(totalRuns).append("\n");
        sb.append("  Running Runs: ").append(runningRuns).append("\n");
        sb.append("  Pending Approvals: ").append(pendingApprovals).append("\n");
        sb.append("  Knowledge Items: ").append(knowledgeItems);
        return sb.toString();
    }

    private String error(String msg) {
        return "Error: " + msg;
    }
}
