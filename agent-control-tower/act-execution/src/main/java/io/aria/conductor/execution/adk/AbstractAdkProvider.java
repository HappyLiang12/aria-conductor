package io.aria.conductor.execution.adk;

import io.aria.conductor.execution.llm.LlmResponse;
import io.aria.conductor.execution.llm.LlmToolCall;
import io.aria.conductor.execution.pipeline.Action;
import io.aria.conductor.execution.pipeline.ActionType;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared base class for {@link AdkProvider} implementations.
 *
 * <p>Provides common tool-call classification logic that is independent
 * of the underlying agent runtime. Subclasses only need to implement
 * the runtime-specific methods ({@link #call}, {@link #prepareAgent},
 * {@link #isHealthy}, etc.).
 */
public abstract class AbstractAdkProvider implements AdkProvider {

    @Override
    public List<Action> parseActionsFromResponse(LlmResponse response) {
        if (!response.hasToolCalls()) {
            return List.of();
        }

        List<Action> actions = new ArrayList<>();
        for (LlmToolCall tc : response.toolCalls()) {
            ActionType type = classifyToolAction(tc.name());
            actions.add(new Action(tc.name(), type, tc.arguments(), tc.id()));
        }
        return actions;
    }

    /**
     * Classify a tool/function name into a risk-level {@link ActionType}.
     *
     * <p>Convention:
     * <ul>
     *   <li>delete / remove / drop → HIGH_RISK</li>
     *   <li>write / create / update / insert / execute / run → WRITE</li>
     *   <li>exec / shell / command → EXECUTE</li>
     *   <li>everything else → READ</li>
     * </ul>
     */
    protected ActionType classifyToolAction(String functionName) {
        String lower = functionName.toLowerCase();
        if (lower.contains("delete") || lower.contains("remove") || lower.contains("drop")) {
            return ActionType.HIGH_RISK;
        }
        if (lower.contains("write") || lower.contains("create") || lower.contains("update")
                || lower.contains("insert") || lower.contains("execute") || lower.contains("run")) {
            return ActionType.WRITE;
        }
        if (lower.contains("exec") || lower.contains("shell") || lower.contains("command")) {
            return ActionType.EXECUTE;
        }
        return ActionType.READ;
    }
}
