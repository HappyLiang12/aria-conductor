package io.aria.conductor.execution.pipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.execution.adk.AdkProviderRegistry;
import io.aria.conductor.execution.engine.RunContext;
import io.aria.conductor.execution.tool.ToolExecutionEngine;
import io.aria.conductor.execution.tool.ToolExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Executes a single action via the ToolExecutionEngine.
 */
@Slf4j
@Component
public class ActionExecutor {

    private final AdkProviderRegistry adkProviderRegistry;
    private final ToolExecutionEngine toolExecutionEngine;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ActionExecutor(AdkProviderRegistry adkProviderRegistry,
                          ToolExecutionEngine toolExecutionEngine) {
        this.adkProviderRegistry = adkProviderRegistry;
        this.toolExecutionEngine = toolExecutionEngine;
    }

    public ActionResult execute(Action action, RunContext ctx) {
        log.info("Executing action: name={}, type={}, toolCallId={}",
                action.name(), action.type(), action.toolCallId());

        try {
            Map<String, Object> args = parseArguments(action.arguments());
            ToolExecutionResult result = toolExecutionEngine.execute(action.name(), args, ctx);

            if (result.isSuccess()) {
                String output = result.getOutput() != null ? result.getOutput() : "";
                log.debug("Action executed successfully: name={}, outputLength={}", action.name(), output.length());
                return ActionResult.success(output);
            } else {
                log.warn("Action execution failed: name={}, error={}", action.name(), result.getError());
                return ActionResult.failed(result.getError());
            }
        } catch (Exception e) {
            log.error("Action execution failed: name={}, error={}", action.name(), e.getMessage(), e);
            return ActionResult.failed(e.getMessage());
        }
    }

    private Map<String, Object> parseArguments(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse action arguments as JSON: {}", e.getMessage());
            throw new IllegalArgumentException("Invalid JSON arguments for tool call: " + e.getMessage(), e);
        }
    }
}
