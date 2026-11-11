package io.aria.conductor.aria.service;

/**
 * The one place a failed turn's human reason is worded and bounded.
 *
 * <p>A failed turn is told twice: live, in the terminal SSE {@code error} payload's
 * {@code reason}, and after a reload, in the synthetic failed-turn entry rebuilt from
 * the run. The panel recognises the second telling of the first by matching the reason
 * text, so the two producers must not each invent their own fallback — when the run
 * recorded a blank {@code errorMessage} they previously said "unknown error" and
 * "原因不明", the dedupe stopped matching, and a duplicate failure bubble returned after
 * a reload. Both callers use {@link #clip(String)} so the reason is the same value by
 * construction.
 *
 * <p>A live cause message is extra information the durable row does not hold: the
 * live path prefers it over the fallback, and only a genuinely blank source reaches
 * {@link #UNKNOWN}.
 */
public final class RunFailureReason {

    /** What a failed turn reports when it recorded no reason at all. */
    public static final String UNKNOWN = "原因不明";

    /** Longest reason either telling carries; both producers shared this bound before. */
    private static final int MAX_CHARS = 300;

    private RunFailureReason() {
    }

    /** {@code reason} bounded to {@link #MAX_CHARS}, or {@link #UNKNOWN} when it is blank. */
    public static String clip(String reason) {
        if (reason == null || reason.isBlank()) {
            return UNKNOWN;
        }
        return reason.length() > MAX_CHARS ? reason.substring(0, MAX_CHARS) : reason;
    }
}
