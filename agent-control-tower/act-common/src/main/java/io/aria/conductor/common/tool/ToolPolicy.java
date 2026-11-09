package io.aria.conductor.common.tool;

/**
 * What authority a tool call requires (spec §6.1, "explicit policy, not naming
 * heuristics"). Moved verbatim from the MCP tool policy registry so the shared
 * tool catalog can carry the reviewed classification.
 */
public enum ToolPolicy {
    WORKER_READ,
    WORKER_WRITE,
    OPERATOR_ONLY,
    GATED
}
