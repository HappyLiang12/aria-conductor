package io.aria.conductor.execution.tool;

import java.util.Map;

public interface ToolHandler {
    String execute(Map<String, Object> arguments);
}
