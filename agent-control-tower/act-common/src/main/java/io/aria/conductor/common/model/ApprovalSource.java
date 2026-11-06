package io.aria.conductor.common.model;

/**
 * Provenance of an {@link Approval}: {@code LEGACY_GATE} for approvals raised by the legacy
 * tool-call gate (the historical path, and the default for every row that predates V60),
 * {@code ACP_PERMISSION} for ACP {@code request_permission} asks raised by a governed ADK
 * provider and surfaced as first-class governance records, and {@code CLARIFICATION} for
 * platform-detected clarification questions holding a run in {@code WAITING_INPUT} (never
 * decidable through the legacy gate flows).
 */
public enum ApprovalSource {
    LEGACY_GATE,
    ACP_PERMISSION,
    /** Platform-detected clarification question holding a run in WAITING_INPUT (2026-10-05). */
    CLARIFICATION
}
