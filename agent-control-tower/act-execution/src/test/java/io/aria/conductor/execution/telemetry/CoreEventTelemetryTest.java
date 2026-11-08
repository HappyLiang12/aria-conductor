package io.aria.conductor.execution.telemetry;

import io.aria.conductor.execution.runtime.CoreEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * F17/F9: the bridge's derived {@code tool.call} / {@code tool.result} /
 * {@code usage} events are mapped onto the persistence seam with the exact
 * payload shapes the bridge emits (packages/qoder-acp-bridge/src/tool-events.ts,
 * usage.ts, main.ts).
 *
 * <p>No Spring context: the adapter is a plain object, and the seam is a mock.
 * The claims here are the mapping rules; the persisted rows are pinned by
 * {@link CoreEventTelemetrySliceTest}.
 */
class CoreEventTelemetryTest {

    /** Exactly what the bridge emits for a tool call whose input fits the cap (object kept). */
    private static final String TOOL_CALL_OBJECT_INPUT = """
            {"toolCallId":"call-obj","toolName":"Bash","kind":"bash","title":"git status",
             "input":{"command":"git status"},"inputBytes":22,"inputTruncated":false}
            """;

    /** A tool call whose input exceeded TOOL_PAYLOAD_LIMIT_BYTES: a string with the suffix marker. */
    private static final String TOOL_CALL_TRUNCATED_INPUT = """
            {"toolCallId":"call-big","toolName":"Read","kind":"read","title":"Read file",
             "input":"line one\\nline two [truncated]","inputBytes":16384,"inputTruncated":true}
            """;

    /** A completed tool result whose recorded output was an object (kept by the cap). */
    private static final String TOOL_RESULT_OBJECT = """
            {"toolCallId":"call-mcp","toolName":"mcp__aria__list","status":"completed",
             "isError":false,"result":{"items":[]},"resultBytes":13,"resultTruncated":false}
            """;

    private static final String TOOL_RESULT_FAILED = """
            {"toolCallId":"call-obj","toolName":"Bash","status":"failed","isError":true,
             "result":"exit code 128","resultBytes":12,"resultTruncated":false}
            """;

    private static final UUID RUN = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private RunTelemetryService telemetry;
    private CoreEventTelemetry adapter;

    @BeforeEach
    void setUp() {
        telemetry = mock(RunTelemetryService.class);
        adapter = new CoreEventTelemetry(telemetry);
    }

    private CoreEvent event(String type, String payloadJson) {
        return new CoreEvent(type, RUN, "session-1", null, payloadJson);
    }

    @Test
    void objectToolInputIsRecordedAsCompactJsonAndTheCallIsNotAnError() {
        assertThat(adapter.record(RUN, event("tool.call", TOOL_CALL_OBJECT_INPUT))).isTrue();

        verify(telemetry).recordToolCall(RUN,
                new RunTelemetryService.ToolCallEvent("call-obj", "Bash", "{\"command\":\"git status\"}", false));
    }

    @Test
    void truncatedToolInputIsRecordedVerbatimWithItsMarker() {
        assertThat(adapter.record(RUN, event("tool.call", TOOL_CALL_TRUNCATED_INPUT))).isTrue();

        verify(telemetry).recordToolCall(RUN, new RunTelemetryService.ToolCallEvent("call-big", "Read",
                "line one\nline two [truncated]", false));
    }

    @Test
    void objectToolResultIsRecordedAsCompactJson() {
        assertThat(adapter.record(RUN, event("tool.result", TOOL_RESULT_OBJECT))).isTrue();

        verify(telemetry).recordToolResult(RUN,
                new RunTelemetryService.ToolResultEvent("call-mcp", "mcp__aria__list", "{\"items\":[]}", false));
    }

    @Test
    void failedToolResultIsRecordedAsAnError() {
        assertThat(adapter.record(RUN, event("tool.result", TOOL_RESULT_FAILED))).isTrue();

        verify(telemetry).recordToolResult(RUN,
                new RunTelemetryService.ToolResultEvent("call-obj", "Bash", "exit code 128", true));
    }

    @Test
    void absentUsageCountersStayNullAndReportedOnesPassThrough() {
        // The ACP usage frame carries input/output only; totalTokens is the null the bridge serializes.
        assertThat(adapter.record(RUN, event("usage", """
                {"promptId":3,"inputTokens":120,"outputTokens":30,"totalTokens":null,"source":"usage"}
                """))).isTrue();

        verify(telemetry).recordUsage(RUN, new RunTelemetryService.UsageEvent(120L, 30L, null));
    }

    @Test
    void genuineZeroUsageStaysZero() {
        assertThat(adapter.record(RUN, event("usage", """
                {"promptId":4,"inputTokens":0,"outputTokens":0,"totalTokens":0,"source":"quota"}
                """))).isTrue();

        verify(telemetry).recordUsage(RUN, new RunTelemetryService.UsageEvent(0L, 0L, 0L));
    }

    @Test
    void usageWithEveryCounterAbsentIsHandedThroughAsUnknown() {
        assertThat(adapter.record(RUN, event("usage", """
                {"promptId":5,"inputTokens":null,"outputTokens":null,"totalTokens":null,"source":"usage"}
                """))).isTrue();

        verify(telemetry).recordUsage(RUN, new RunTelemetryService.UsageEvent(null, null, null));
    }

    @Test
    void negativeOrNonIntegralCountersAreNeverInventedAsZero() {
        assertThat(adapter.record(RUN, event("usage", """
                {"promptId":6,"inputTokens":-3,"outputTokens":1.5,"totalTokens":"7"}
                """))).isTrue();

        verify(telemetry).recordUsage(RUN, new RunTelemetryService.UsageEvent(null, null, null));
    }

    @Test
    void malformedJsonIsSkippedAndNeverThrows() {
        assertThat(adapter.record(RUN, event("tool.call", "{\"toolCallId\":"))).isFalse();
        assertThat(adapter.record(RUN, event("usage", "not json at all"))).isFalse();

        verifyNoInteractions(telemetry);
    }

    @Test
    void aPayloadThatIsNotAJsonObjectIsSkipped() {
        assertThat(adapter.record(RUN, event("tool.result", "[1,2,3]"))).isFalse();
        assertThat(adapter.record(RUN, event("usage", "\"zero\""))).isFalse();

        verifyNoInteractions(telemetry);
    }

    @Test
    void eventTypesOwnedByAnotherPathAreNeverRecordedHere() {
        // permission.request carries the same toolCallId/toolName/input; recording it would duplicate
        // the tool.call row that the same call already produced.
        assertThat(adapter.record(RUN, event("permission.request", """
                {"requestId":7,"toolCallId":"call-obj","toolName":"Bash","input":{"command":"git status"}}
                """))).isFalse();
        assertThat(adapter.record(RUN, event("prompt.result", "{}"))).isFalse();
        assertThat(adapter.record(RUN, event("opencode.progress", "{}"))).isFalse();

        verifyNoInteractions(telemetry);
    }

    @Test
    void anEventWithoutAnyRunIdIsSkipped() {
        assertThat(adapter.record(null, new CoreEvent("usage", null, null, null,
                "{\"inputTokens\":1}"))).isFalse();

        verifyNoInteractions(telemetry);
    }

    @Test
    void aPersistenceFailureIsReportedAndNeverPropagates() {
        doThrow(new IllegalStateException("database is down"))
                .when(telemetry).recordUsage(any(), any());

        assertThat(adapter.record(RUN, event("usage", "{\"inputTokens\":1}"))).isFalse();

        // The event was attempted exactly once and the failure stopped at the adapter: the core
        // event loop must never fail a run because observability could not be persisted.
        verify(telemetry).recordUsage(RUN, new RunTelemetryService.UsageEvent(1L, null, null));
        verify(telemetry, never()).recordToolCall(any(), any());
    }
}
