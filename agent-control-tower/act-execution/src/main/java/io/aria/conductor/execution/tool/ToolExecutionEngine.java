package io.aria.conductor.execution.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.model.ToolDefinition;
import io.aria.conductor.common.repository.ToolDefinitionRepository;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ToolExecutionResult execute(String toolName, Map<String, Object> arguments) {
        ToolDefinition tool = toolRepo.findByName(toolName).orElse(null);
        if (tool == null) return ToolExecutionResult.failed("Unknown tool: " + toolName);
        if (!tool.isEnabled()) return ToolExecutionResult.failed("Tool is disabled: " + toolName);

        return switch (tool.getSandboxMode()) {
            case "NONE" -> executeViaHandler(tool, arguments);
            case "DOCKER", "PROCESS" -> {
                if (sandboxRunner.isSandboxAvailable()) {
                    yield executeInSandbox(tool, arguments);
                }
                log.info("Sandbox unavailable for {}, falling back to handler", toolName);
                yield executeViaHandler(tool, arguments);
            }
            default -> ToolExecutionResult.failed("Unknown sandbox mode: " + tool.getSandboxMode());
        };
    }

    private ToolExecutionResult executeViaHandler(ToolDefinition tool, Map<String, Object> arguments) {
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
            handlerArgs.put("toolName", tool.getName());
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
