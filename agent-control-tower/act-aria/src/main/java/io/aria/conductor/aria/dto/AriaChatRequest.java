package io.aria.conductor.aria.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AriaChatRequest {

    /**
     * @deprecated Use {@link #conversationId} instead. Kept for backward compatibility
     *             with clients that haven't migrated. Will be removed in a future version.
     */
    @Deprecated
    private String sessionId;

    /**
     * Persistent conversation identifier maintained by the frontend (localStorage).
     * Echoed back in the response and written to run/audit_log tables for traceability.
     */
    private String conversationId;

    @NotBlank(message = "Message is required")
    private String message;

    /**
     * Optional client-provided conversation history. When supplied, these messages are
     * prepended to the LLM message list so Aria has context of prior turns even when the
     * server-side session has been recycled (e.g., after a backend restart or new sessionId).
     * Each entry should have role = "user" | "assistant" and a content string.
     */
    private List<ChatMessage> history;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatMessage {
        private String role;
        private String content;
    }
}
