package io.aria.conductor.execution.sandbox;

public record SandboxResult(boolean success, String output, String error) {
    public static SandboxResult success(String output) { return new SandboxResult(true, output, null); }
    public static SandboxResult failed(String error) { return new SandboxResult(false, null, error); }
    public boolean isSuccess() { return success; }
    public String getOutput() { return output; }
    public String getError() { return error; }
}