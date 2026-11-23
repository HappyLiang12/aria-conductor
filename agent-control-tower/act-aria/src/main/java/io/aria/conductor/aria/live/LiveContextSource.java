package io.aria.conductor.aria.live;

import java.util.List;

/**
 * The conversation history the bridge receives with {@code hello} (spec §"Bridge process",
 * Task 2 Step 4) so a re-opened Qwen session can continue the conversation it is looking at.
 *
 * <p>It is a seam of its own so the handler never touches a repository: a deployment with no
 * conversation store (a test, a fresh install) supplies an empty context and the handshake
 * still works.
 */
@FunctionalInterface
public interface LiveContextSource {

    /** The most the bridge is seeded with: the last 20 user/assistant text entries. */
    int MAX_ENTRIES = 20;

    /** @return the seeded entries, oldest first; empty — never null — when unknown */
    List<Entry> context(String conversationId);

    /** One user or assistant turn, exactly as the timeline shows it. */
    record Entry(String role, String text) {
    }
}
