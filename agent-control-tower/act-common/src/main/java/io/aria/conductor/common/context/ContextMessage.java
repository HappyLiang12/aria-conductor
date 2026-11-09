package io.aria.conductor.common.context;

import java.util.Objects;

/**
 * One bounded conversation turn carried into a context envelope. The id of the
 * tool call a message answers is preserved so tool results stay attached to
 * their call.
 */
public record ContextMessage(String role, String content, String toolCallId) {

    public ContextMessage {
        Objects.requireNonNull(role, "role");
        content = content == null ? "" : content;
    }
}
