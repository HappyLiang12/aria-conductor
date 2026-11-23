package io.aria.conductor.aria.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** One row of the conversation switcher: the conversation and a readable first prompt. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationListItem {
    private String conversationId;
    private String title;
    private Instant lastMessageAt;
    private long runCount;
}
