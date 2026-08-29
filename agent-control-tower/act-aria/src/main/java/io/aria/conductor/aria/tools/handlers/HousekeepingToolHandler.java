package io.aria.conductor.aria.tools.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.execution.approval.ApprovalDecision;
import io.aria.conductor.execution.approval.ApprovalGate;
import io.aria.conductor.execution.engine.RunContext;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.CategoryItem;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.CategoryReceipt;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.CategorySummary;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.Exclusions;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.HousekeepingReceipt;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.HousekeepingRequest;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.ScanResult;
import io.aria.conductor.execution.housekeeping.HousekeepingService;
import io.aria.conductor.execution.pipeline.Action;
import io.aria.conductor.execution.pipeline.ActionType;
import io.aria.conductor.execution.tool.ToolHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Housekeeping S5 (H4): Aria-facing cleanup tools.
 *
 * <p>{@code housekeeping_scan} is strictly read-only. {@code housekeeping_execute}
 * is DESTRUCTIVE and always blocks on the human {@link ApprovalGate} (the agent
 * can request, never self-approve); exclusions from the conversation (e.g.
 * "keep the BA analysis card") are passed through to the service.
 */
@Slf4j
@Component("housekeepingToolHandler")
public class HousekeepingToolHandler implements ToolHandler {

    private final HousekeepingService housekeepingService;
    private final ApprovalGate approvalGate;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public HousekeepingToolHandler(HousekeepingService housekeepingService, ApprovalGate approvalGate) {
        this.housekeepingService = housekeepingService;
        this.approvalGate = approvalGate;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String toolName = Objects.toString(arguments.get("toolName"), "");
        try {
            return switch (toolName) {
                case "housekeeping_scan" -> scan();
                case "housekeeping_execute" -> executeGated(arguments);
                default -> error("Unknown tool: " + toolName);
            };
        } catch (Exception e) {
            log.error("HousekeepingToolHandler failed for {}", toolName, e);
            return error(e.getMessage());
        }
    }

    private String scan() {
        ScanResult res = housekeepingService.scan(true, Exclusions.empty());
        StringBuilder sb = new StringBuilder("Housekeeping scan — leftovers found:\n");
        for (CategorySummary c : res.categories()) {
            sb.append("- ").append(c.key()).append(": ").append(c.count()).append(" item(s)");
            String samples = c.preview().stream().limit(3)
                    .map(CategoryItem::title)
                    .filter(t -> t != null && !t.isEmpty())
                    .collect(Collectors.joining(", "));
            if (!samples.isEmpty()) {
                sb.append(" (e.g. ").append(samples).append(")");
            }
            sb.append("\n");
        }
        sb.append("Call housekeeping_execute with categories/exclusions; a human must approve it.");
        return sb.toString();
    }

    private String executeGated(Map<String, Object> args) {
        Object ctxObj = args.get("_runContext");
        if (!(ctxObj instanceof RunContext ctx)) {
            return error("housekeeping_execute can only be invoked within an agent run context");
        }
        List<String> categories = asStringList(args.get("categories"));
        if (categories.isEmpty()) {
            return error("Missing required parameter: categories");
        }
        boolean includeStuck = Boolean.parseBoolean(Objects.toString(args.get("includeStuck"), "false"));
        Exclusions exclusions = parseExclusions(args.get("exclusions"));

        String payload;
        try {
            payload = MAPPER.writeValueAsString(Map.of(
                    "categories", categories, "includeStuck", includeStuck));
        } catch (Exception e) {
            return error("Failed to build approval payload");
        }
        Action action = new Action("housekeeping_execute", ActionType.HIGH_RISK, payload,
                ctx.getCurrentToolCallId() != null ? ctx.getCurrentToolCallId().toString() : null);

        ApprovalDecision decision = approvalGate.requestApproval(action, ctx);
        if (!decision.isApproved()) {
            return "DENIED: " + (decision.reason() != null ? decision.reason() : "Human denied the cleanup.");
        }

        HousekeepingReceipt receipt = housekeepingService.execute(
                new HousekeepingRequest(categories, includeStuck, exclusions, true));
        StringBuilder sb = new StringBuilder("Housekeeping executed:\n");
        for (CategoryReceipt r : receipt.categories()) {
            sb.append("- ").append(r.key())
                    .append(": cleared=").append(r.cleared())
                    .append(" failed=").append(r.failed())
                    .append(" skipped=").append(r.skipped())
                    .append("\n");
        }
        return sb.toString().trim();
    }

    private List<String> asStringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(Objects::toString).toList();
    }

    private Exclusions parseExclusions(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Exclusions.empty();
        }
        return new Exclusions(
                asStringList(map.get("runIds")),
                asStringList(map.get("kanbanItemIds")),
                asStringList(map.get("agentIds")),
                asStringList(map.get("approvalIds")));
    }

    private String error(String msg) {
        return "Error: " + msg;
    }
}
