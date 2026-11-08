package io.aria.conductor.execution.runtime.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F29: the per-prompt usage bookkeeping is proven without a bridge client. The bridge's separate
 * {@code usage} event reports the counts the pinned CLI hides beside a placeholder ACP usage block,
 * so the bookkeeping has to say exactly which source is authoritative: a reported counter (zero
 * included) beats the terminal frame, a terminal-only counter is preserved as it is, and an absent
 * or non-numeric member is unknown rather than zero.
 */
class QoderCoreSessionReportedUsageTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final QoderCoreSession.ReportedUsage usage = new QoderCoreSession.ReportedUsage();

    @Test
    void aReportedCounterBeatsTheTerminalPlaceholderZero() {
        usage.record(json("{\"inputTokens\":12,\"outputTokens\":7,\"totalTokens\":19}"));

        JsonNode placeholder = json("{\"inputTokens\":0,\"outputTokens\":0,\"totalTokens\":0}");

        assertThat(usage.inputTokensOr(placeholder))
                .as("the real quota count, not the pinned CLI's placeholder zero").isEqualTo(12L);
        assertThat(usage.outputTokensOr(placeholder)).isEqualTo(7L);
    }

    @Test
    void aTerminalOnlyZeroIsPreservedAsZero() {
        JsonNode terminal = json("{\"inputTokens\":0,\"outputTokens\":0}");

        assertThat(usage.inputTokensOr(terminal))
                .as("a genuinely reported zero is a zero, never unknown").isZero();
        assertThat(usage.outputTokensOr(terminal)).isZero();
    }

    @Test
    void anAbsentMemberStaysNull() {
        assertThat(usage.inputTokensOr(json("{\"outputTokens\":3}"))).isNull();
        assertThat(usage.outputTokensOr(json("{}"))).isNull();
    }

    @Test
    void nonNumericOrAbsentReportedMembersStayNull() {
        usage.record(json("{\"inputTokens\":\"12\",\"outputTokens\":null}"));

        assertThat(usage.inputTokensOr(json("{}")))
                .as("a non-numeric report is unknown, never zero").isNull();
        assertThat(usage.outputTokensOr(json("{}"))).isNull();

        assertThat(usage.inputTokensOr(json("{\"inputTokens\":99}")))
                .as("a member the report did not carry leaves the terminal counter authoritative")
                .isEqualTo(99L);
    }

    @Test
    void clearMakesALaterTerminalCounterAuthoritative() {
        usage.record(json("{\"inputTokens\":12,\"outputTokens\":7}"));

        usage.clear();

        assertThat(usage.inputTokensOr(json("{\"inputTokens\":5,\"outputTokens\":2}")))
                .as("a cleared prompt report can never be carried to the next prompt").isEqualTo(5L);
        assertThat(usage.outputTokensOr(json("{\"inputTokens\":5,\"outputTokens\":2}"))).isEqualTo(2L);
        assertThat(usage.inputTokensOr(json("{}"))).isNull();
    }

    @Test
    void isCurrentPromptMatchesOnlyTheAcknowledgedPrompt() {
        assertThat(QoderCoreSession.isCurrentPrompt(7, json("{\"promptId\":7}"))).isTrue();
        assertThat(QoderCoreSession.isCurrentPrompt(7, json("{\"promptId\":8}"))).isFalse();
        assertThat(QoderCoreSession.isCurrentPrompt(7, json("{}")))
                .as("a frame without a prompt id can never match an acknowledged prompt").isFalse();
    }

    @Test
    void isCurrentPromptAcceptsThePreAckWindow() {
        assertThat(QoderCoreSession.isCurrentPrompt(-1, json("{}")))
                .as("no prompt id is acknowledged yet, so the first frame belongs to this prompt").isTrue();
        assertThat(QoderCoreSession.isCurrentPrompt(-1, json("{\"promptId\":3}"))).isTrue();
    }

    private static JsonNode json(String payload) {
        try {
            return JSON.readTree(payload);
        } catch (Exception e) {
            throw new IllegalStateException("unreadable test payload: " + payload, e);
        }
    }
}
