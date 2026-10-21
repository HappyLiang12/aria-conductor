package io.aria.conductor.common.model;

/**
 * Bounds operator-facing text before it is persisted to
 * {@code runs.error_message}, which is VARCHAR(2000). Failing the completion
 * because a diagnostic message is too long would hide the original failure, so
 * an oversized message is truncated with an explicit marker instead.
 */
public final class RunErrorText {

    /** Width of the {@code runs.error_message} column. */
    public static final int MAX_PERSISTED_LENGTH = 2000;

    /** Characters kept when a message must be truncated, leaving room for the marker. */
    private static final int TRUNCATED_KEEP = 1850;

    private RunErrorText() {
    }

    public static String bound(String message) {
        if (message == null) {
            return "";
        }
        if (message.length() <= MAX_PERSISTED_LENGTH) {
            return message;
        }
        return message.substring(0, TRUNCATED_KEEP) + "... [truncated, "
                + (message.length() - TRUNCATED_KEEP) + " more characters]";
    }
}
