package io.aria.conductor.execution.approval;

/**
 * Which execution boundary a normalized permission request belongs to
 * (spec §6.3): {@code NATIVE_TOOL} is a tool the core executes itself and the
 * reply therefore goes back to the owning core session; {@code PLATFORM_MCP} is
 * a platform MCP tool call the run asks to make, and an allow decision
 * authorizes exactly one matching call through the platform boundary.
 */
public enum PermissionTarget {
    NATIVE_TOOL,
    PLATFORM_MCP
}
