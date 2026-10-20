package io.aria.conductor.common.exception;

/**
 * The operator surface that can fix an actionable failure. The dashboard maps
 * each surface to a concrete action (open a Configure tab or the Crew view)
 * instead of parsing the human-readable message.
 */
public enum ErrorSurface {
    LLM_PROVIDERS,
    RUNTIME,
    SYSTEM_CONFIG,
    CREW
}
