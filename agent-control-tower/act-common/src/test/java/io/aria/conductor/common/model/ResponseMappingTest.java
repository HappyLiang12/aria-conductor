package io.aria.conductor.common.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the DTO factory/mapping logic in {@link ToolCallResponse#from} and
 * {@link TrajectoryResponse#from}, and the tool-call display formatting in
 * {@link TrajectoryResponse#formatToolCallsForDisplay} (JSON array -> "name(args)" with fallbacks).
 */
class ResponseMappingTest {

    @Test
    void toolCallResponse_from_copiesFieldsAndStringifiesStatus() {
        UUID id = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Instant created = Instant.parse("2021-05-05T10:00:00Z");
        ToolCall call = ToolCall.builder()
                .id(id).runId(runId).toolName("web_search")
                .arguments("{\"q\":\"x\"}").result("ok")
                .status(ToolCallStatus.COMPLETED).latencyMs(42).createdAt(created)
                .build();

        ToolCallResponse response = ToolCallResponse.from(call);

        assertThat(response.getId()).isEqualTo(id);
        assertThat(response.getRunId()).isEqualTo(runId);
        assertThat(response.getToolName()).isEqualTo("web_search");
        assertThat(response.getArguments()).isEqualTo("{\"q\":\"x\"}");
        assertThat(response.getResult()).isEqualTo("ok");
        assertThat(response.getStatus()).isEqualTo("COMPLETED");
        assertThat(response.getLatencyMs()).isEqualTo(42);
        assertThat(response.getCreatedAt()).isEqualTo(created);
    }

    @Test
    void trajectoryResponse_from_copiesFieldsAndFormatsToolCalls() {
        UUID id = UUID.randomUUID();
        SessionTrajectory trajectory = SessionTrajectory.builder()
                .id(id).runId(UUID.randomUUID()).turnNumber(2).role("assistant")
                .content("thinking...")
                .toolCalls("[{\"name\":\"web_search\",\"arguments\":\"{\\\"q\\\":\\\"cats\\\"}\"}]")
                .toolCallId("tc-1").inputTokens(10).outputTokens(20).latencyMs(5)
                .createdAt(Instant.now())
                .build();

        TrajectoryResponse response = TrajectoryResponse.from(trajectory);

        assertThat(response.getId()).isEqualTo(id);
        assertThat(response.getRole()).isEqualTo("assistant");
        assertThat(response.getTurnNumber()).isEqualTo(2);
        assertThat(response.getToolCalls()).isEqualTo("web_search({\"q\":\"cats\"})");
    }

    @Test
    void formatToolCallsForDisplay_returnsNullForNull() {
        assertThat(TrajectoryResponse.formatToolCallsForDisplay(null)).isNull();
    }

    @Test
    void formatToolCallsForDisplay_returnsRawForBlank() {
        assertThat(TrajectoryResponse.formatToolCallsForDisplay("   ")).isEqualTo("   ");
    }

    @Test
    void formatToolCallsForDisplay_returnsRawForNonArrayText() {
        assertThat(TrajectoryResponse.formatToolCallsForDisplay("plain text")).isEqualTo("plain text");
    }

    @Test
    void formatToolCallsForDisplay_joinsMultipleCallsWithCommas() {
        String raw = "[{\"name\":\"a\",\"arguments\":\"{}\"},{\"name\":\"b\",\"arguments\":\"{\\\"x\\\":1}\"}]";
        assertThat(TrajectoryResponse.formatToolCallsForDisplay(raw))
                .isEqualTo("a({}), b({\"x\":1})");
    }

    @Test
    void formatToolCallsForDisplay_usesFallbacksForMissingFields() {
        // Missing name/arguments -> "?" and "{}" defaults.
        assertThat(TrajectoryResponse.formatToolCallsForDisplay("[{}]")).isEqualTo("?({})");
    }

    @Test
    void formatToolCallsForDisplay_returnsRawWhenJsonIsMalformed() {
        String malformed = "[not valid json";
        assertThat(TrajectoryResponse.formatToolCallsForDisplay(malformed)).isEqualTo(malformed);
    }

    @Test
    void formatToolCallsForDisplay_returnsRawWhenJsonIsObjectNotArray() {
        // Starts with '[' check fails for objects, so passed through unchanged.
        assertThat(TrajectoryResponse.formatToolCallsForDisplay("{\"name\":\"a\"}"))
                .isEqualTo("{\"name\":\"a\"}");
    }
}
