package io.aria.conductor.execution.tool;

public record ToolExecutionResult(boolean success, String output, String error) {
    public static ToolExecutionResult success(String output) { return new ToolExecutionResult(true, output, null); }
    public static ToolExecutionResult failed(String error) { return new ToolExecutionResult(false, null, error); }
    public boolean isSuccess() { return success; }
    public String getOutput() { return output; }
    public String getError() { return error; }
}
