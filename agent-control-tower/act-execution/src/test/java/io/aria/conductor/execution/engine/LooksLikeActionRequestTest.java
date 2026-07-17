package io.aria.conductor.execution.engine;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD: ACTION_PATTERN should NOT match informational-only verbs (list, get, show).
 * These verbs appear in queries where the LLM correctly answers with text — no retry needed.
 */
class LooksLikeActionRequestTest {

    // Match the pattern from AgentLoopEngine (list|get|show removed per CR fix)
    private static final Pattern ACTION_PATTERN = Pattern.compile(
            "\\b(create|start|update|delete|remove|add|run|" +
            "execute|generate|build|deploy|cancel|pause|resume|retire|" +
            "store|query|amend|init|submit|transition|approve|reject)\\b");

    private static boolean looksLikeActionRequest(String prompt) {
        if (prompt == null) return false;
        return ACTION_PATTERN.matcher(prompt.toLowerCase()).find();
    }

    @Test
    void informationalQueriesShouldNotTriggerRetry() {
        // These prompts contain "list", "get", "show" — informational queries
        // where LLM correctly responds with text. Retry would be wasteful.
        assertThat(looksLikeActionRequest("list all agents"))
                .as("'list all agents' should NOT trigger retry")
                .isFalse();
        assertThat(looksLikeActionRequest("get dashboard summary"))
                .as("'get dashboard summary' should NOT trigger retry")
                .isFalse();
        assertThat(looksLikeActionRequest("show me what's happening"))
                .as("'show me what's happening' should NOT trigger retry")
                .isFalse();
        assertThat(looksLikeActionRequest("what can you do"))
                .as("'what can you do' should NOT trigger retry")
                .isFalse();
        assertThat(looksLikeActionRequest("tell me about the system"))
                .as("'tell me about the system' should NOT trigger retry")
                .isFalse();
    }

    @Test
    void actionRequestsShouldTriggerRetry() {
        // These prompts contain action verbs — user clearly wants tools executed
        assertThat(looksLikeActionRequest("create a new agent"))
                .as("'create a new agent' should trigger retry")
                .isTrue();
        assertThat(looksLikeActionRequest("start a run for agent X"))
                .as("'start a run for agent X' should trigger retry")
                .isTrue();
        assertThat(looksLikeActionRequest("update the kanban item"))
                .as("'update the kanban item' should trigger retry")
                .isTrue();
        assertThat(looksLikeActionRequest("delete the old report"))
                .as("'delete the old report' should trigger retry")
                .isTrue();
        assertThat(looksLikeActionRequest("cancel the running job"))
                .as("'cancel the running job' should trigger retry")
                .isTrue();
        assertThat(looksLikeActionRequest("remove user from group"))
                .as("'remove user from group' should trigger retry")
                .isTrue();
    }
}
