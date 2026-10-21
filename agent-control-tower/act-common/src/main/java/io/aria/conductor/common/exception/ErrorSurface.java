package io.aria.conductor.common.exception;

/**
 * The operator surface that can fix an actionable failure. The dashboard maps
 * each surface to a concrete action (open a Configure tab or the Crew view)
 * instead of parsing the human-readable message.
 *
 * <p>The names are persisted verbatim in {@code runs.error_surface} and served
 * unchanged over HTTP, so they are immutable: renaming one requires a data
 * migration of the stored rows, not only a code change. Adding a value is safe
 * once the dashboard serves an action for it -- the dashboard's error-surface
 * contract test fails the frontend build otherwise.
 */
public enum ErrorSurface {
    LLM_PROVIDERS,
    RUNTIME,
    SYSTEM_CONFIG,
    CREW
}
