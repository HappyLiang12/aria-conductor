package io.aria.conductor.execution.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.model.PackKind;
import io.aria.conductor.common.model.ToolDefinition;
import io.aria.conductor.common.model.VersionStatus;
import io.aria.conductor.common.repository.ToolDefinitionRepository;
import io.aria.conductor.execution.engine.RunContext;
import io.aria.conductor.execution.sandbox.SandboxResult;
import io.aria.conductor.execution.sandbox.SandboxRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ToolExecutionEngine {

    private final ToolDefinitionRepository toolRepo;
    private final SandboxRunner sandboxRunner;
    private final Map<String, ToolHandler> handlers;
    private final WorkspaceManager workspaceManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ToolExecutionResult execute(String toolName, Map<String, Object> arguments) {
        return execute(toolName, arguments, null);
    }

    public ToolExecutionResult execute(String toolName, Map<String, Object> arguments, RunContext ctx) {
        ToolDefinition tool = toolRepo.findByName(toolName).orElse(null);
        if (tool == null) return ToolExecutionResult.failed("Unknown tool: " + toolName);
        if (!tool.isEnabled()) return ToolExecutionResult.failed("Tool is disabled: " + toolName);

        // Layer A registration pre-check: refuse unless APPROVED (legacy tools backfilled APPROVED)
        if (tool.getStatus() != null && tool.getStatus() != VersionStatus.APPROVED) {
            return ToolExecutionResult.failed("Tool not approved for execution: " + toolName + " (status=" + tool.getStatus() + ")");
        }

        // Kind dispatch: MCP and AGENT kinds are reserved for Phase 2/3
        if (tool.getKind() == PackKind.MCP) {
            return ToolExecutionResult.failed("MCP pack kind not yet supported (Phase 2): " + toolName);
        }
        if (tool.getKind() == PackKind.AGENT) {
            return ToolExecutionResult.failed("AGENT pack kind not yet supported (Phase 3): " + toolName);
        }

        return switch (tool.getSandboxMode()) {
            case "NONE" -> executeViaHandler(tool, arguments, ctx);
            case "DOCKER", "PROCESS" -> {
                if (sandboxRunner.isSandboxAvailable()) {
                    yield executeInSandbox(tool, arguments);
                }
                log.info("Sandbox unavailable for {}, falling back to handler", toolName);
                yield executeViaHandler(tool, arguments, ctx);
            }
            default -> ToolExecutionResult.failed("Unknown sandbox mode: " + tool.getSandboxMode());
        };
    }

    private ToolExecutionResult executeViaHandler(ToolDefinition tool, Map<String, Object> arguments, RunContext ctx) {
        String handlerName = tool.getHandlerClass();
        if (handlerName == null || !handlers.containsKey(handlerName)) {
            if (tool.getScript() != null) return executeInSandbox(tool, arguments);
            return ToolExecutionResult.failed("No handler for tool: " + tool.getName());
        }
        try {
            // Dispatcher handlers (agentToolHandler, dashboardToolHandler, runToolHandler, ...) route on the
            // "toolName" argument; the pipeline only forwards the LLM's arguments, so inject the resolved
            // tool name here. Harmless for single-purpose handlers that ignore it.
            Map<String, Object> handlerArgs = new java.util.HashMap<>(arguments != null ? arguments : Map.of());
            // Strip reserved keys to prevent LLM from forging internal context
            handlerArgs.keySet().removeIf(k -> k.startsWith("_"));
            handlerArgs.put("toolName", tool.getName());
            // Inject run context for workspace-aware handlers (null-safe: legacy path unchanged).
            // Use-site workspace guarantee (#26): if the context has no workspace dir yet, resolve it
            // via the shared idempotent WorkspaceManager.getOrProvision contract (same entry point as
            // the loop-level pre-provision), so git-pack/file handlers always receive _workspaceDir.
            if (ctx != null) {
                String workspaceDir = ctx.getWorkspaceDir();
                if ((workspaceDir == null || workspaceDir.isBlank()) && ctx.getRunId() != null) {
                    try {
                        workspaceDir = workspaceManager.getOrProvision(ctx.getRunId());
                        ctx.setWorkspaceDir(workspaceDir);
                    } catch (Exception e) {
                        log.error("Workspace provisioning failed for run {} (tool {}): {}",
                                ctx.getRunId(), tool.getName(), e.getMessage());
                    }
                }
                if (workspaceDir != null && !workspaceDir.isBlank()) handlerArgs.put("_workspaceDir", workspaceDir);
                if (ctx.getRunId() != null) handlerArgs.put("_runId", ctx.getRunId().toString());
                if (ctx.getCurrentToolCallId() != null) handlerArgs.put("_toolCallId", ctx.getCurrentToolCallId().toString());
                handlerArgs.put("_runContext", ctx);
            }
            String result = handlers.get(handlerName).execute(handlerArgs);
            return ToolExecutionResult.success(result);
        } catch (Exception e) {
            log.error("Handler failed for {}", tool.getName(), e);
            return ToolExecutionResult.failed(e.getMessage());
        }
    }

    private ToolExecutionResult executeInSandbox(ToolDefinition tool, Map<String, Object> arguments) {
        if (!sandboxRunner.isSandboxAvailable())
            return ToolExecutionResult.failed("Sandbox unavailable");
        try {
            String argsJson = objectMapper.writeValueAsString(arguments);
            String script = tool.getScript() != null ? tool.getScript().replace("{{TOOL_ARGS}}", argsJson) : argsJson;
            SandboxResult result = sandboxRunner.execute(script, tool.getScriptType(), 256, "0.5", tool.getTimeoutMs());
            return result.isSuccess() ? ToolExecutionResult.success(result.getOutput()) : ToolExecutionResult.failed(result.getError());
        } catch (Exception e) { return ToolExecutionResult.failed(e.getMessage()); }
    }
}
