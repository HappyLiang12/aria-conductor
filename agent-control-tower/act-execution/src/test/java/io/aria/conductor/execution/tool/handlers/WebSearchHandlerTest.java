package io.aria.conductor.execution.tool.handlers;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behaviour tests for {@link WebSearchHandler} — a placeholder handler that validates its
 * required {@code query} parameter and echoes a "pending configuration" notice.
 */
class WebSearchHandlerTest {

    private final WebSearchHandler handler = new WebSearchHandler();

    @Test
    void execute_rejectsMissingQuery() {
        assertThat(handler.execute(Map.of())).isEqualTo("Error: Missing required parameter: query");
        assertThat(handler.execute(Map.of("query", ""))).isEqualTo("Error: Missing required parameter: query");
    }

    @Test
    void execute_echoesQueryInPendingNotice() {
        assertThat(handler.execute(Map.of("query", "aria conductor")))
                .isEqualTo("Web search for 'aria conductor' — feature pending search API configuration.");
    }
}
