package io.aria.conductor.mcp.tools;

import io.aria.conductor.execution.housekeeping.HousekeepingModel.Exclusions;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.HousekeepingReceipt;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.HousekeepingRequest;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.ScanResult;
import io.aria.conductor.execution.housekeeping.HousekeepingService;
import io.aria.conductor.execution.mcp.McpProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class OpsTools implements McpTool {

    private static final Set<String> VALID_CATEGORIES =
            Set.of("kanban", "agents", "approvals", "stuck", "runs");

    private final HousekeepingService housekeepingService;
    private final McpProperties mcpProperties;

    @Tool(name = "housekeeping_scan",
            description = "Preview leftover resources by category (runs, stuck, kanban, agents, approvals) without deleting anything. Read-only; the stuck category is only reported when includeStuck is true.")
    public String housekeepingScan(
            @ToolParam(description = "Include runs stuck waiting on a pending approval", required = false) boolean includeStuck,
            @ToolParam(description = "Run ids to keep", required = false) List<String> excludeRunIds,
            @ToolParam(description = "Kanban item ids to keep", required = false) List<String> excludeKanbanItemIds,
            @ToolParam(description = "Agent ids to keep", required = false) List<String> excludeAgentIds,
            @ToolParam(description = "Approval ids to keep", required = false) List<String> excludeApprovalIds) {
        try {
            ScanResult result = housekeepingService.scan(includeStuck,
                    exclusions(excludeRunIds, excludeKanbanItemIds, excludeAgentIds, excludeApprovalIds));
            return ToolResponses.ok(result);
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("HOUSEKEEPING_SCAN_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    // Deliberate deviation from HousekeepingToolHandler.executeGated: this MCP tool
    // executes directly on confirm=true with no ApprovalGate, matching REST parity
    // (HousekeepingController only requires confirm). The Aria gated path needs a
    // RunContext and can block for up to 30 minutes, neither of which MCP can supply.
    @Tool(name = "housekeeping_execute",
            description = "DESTRUCTIVE and irreversible: delete leftovers in the given categories (kanban, agents, approvals, stuck, runs). Requires confirm=true and at least one category. No human-approval gate is applied — the explicit confirm flag is the only gate.")
    public String housekeepingExecute(
            @ToolParam(description = "Category keys to clear: kanban, agents, approvals, stuck, runs") List<String> categories,
            @ToolParam(description = "Include stuck runs; required for the stuck category to act", required = false) boolean includeStuck,
            @ToolParam(description = "Run ids to keep", required = false) List<String> excludeRunIds,
            @ToolParam(description = "Kanban item ids to keep", required = false) List<String> excludeKanbanItemIds,
            @ToolParam(description = "Agent ids to keep", required = false) List<String> excludeAgentIds,
            @ToolParam(description = "Approval ids to keep", required = false) List<String> excludeApprovalIds,
            @ToolParam(description = "Explicit confirmation; false is rejected") boolean confirm) {
        try {
            validateCategories(categories);
            HousekeepingRequest request = new HousekeepingRequest(categories, includeStuck,
                    exclusions(excludeRunIds, excludeKanbanItemIds, excludeAgentIds, excludeApprovalIds), confirm);
            HousekeepingReceipt receipt = housekeepingService.execute(request);
            return ToolResponses.ok(receipt);
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (IllegalStateException e) {
            return ToolResponses.error("CONFLICT", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("HOUSEKEEPING_EXECUTE_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    private static void validateCategories(List<String> categories) {
        if (categories == null || categories.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one category is required. Valid: kanban, agents, approvals, stuck, runs");
        }
        for (String category : categories) {
            if (category == null || !VALID_CATEGORIES.contains(category)) {
                throw new IllegalArgumentException("Invalid category '" + category
                        + "'. Valid: kanban, agents, approvals, stuck, runs");
            }
        }
    }

    private static Exclusions exclusions(List<String> runIds, List<String> kanbanItemIds,
                                         List<String> agentIds, List<String> approvalIds) {
        return new Exclusions(
                runIds == null ? List.of() : runIds,
                kanbanItemIds == null ? List.of() : kanbanItemIds,
                agentIds == null ? List.of() : agentIds,
                approvalIds == null ? List.of() : approvalIds);
    }
}
