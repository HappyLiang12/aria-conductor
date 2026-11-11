package io.aria.conductor.execution.telemetry;

import io.aria.conductor.execution.engine.RunStreamRegistry;
import io.aria.conductor.execution.runtime.CoreEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

    // ---------------------------------------------------------------- live tool signals

    /**
     * A real {@link SseEmitter} that records the frames it was asked to send: the activity
     * strip's producer is judged on the wire frame, not on an internal call.
     */
    static final class RecordingStream extends SseEmitter {
        final List<SseEventBuilder> frames = new CopyOnWriteArrayList<>();

        RecordingStream() {
            super(0L);
        }

        @Override
        public void send(SseEventBuilder builder) {
            frames.add(builder);
        }
    }

    /** The event name of a frame; SSE writes it as an {@code event:} line. */
    private static String rendered(SseEmitter.SseEventBuilder frame) {
        StringBuilder text = new StringBuilder();
        for (ResponseBodyEmitter.DataWithMediaType part : frame.build()) {
            if (part.getMediaType() == null || MediaType.TEXT_PLAIN.includes(part.getMediaType())) {
                text.append(part.getData());
            }
        }
        return text.toString();
    }

    /** The payload object of a frame, as it was handed over with {@code application/json}. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> payloadOf(SseEmitter.SseEventBuilder frame) {
        return (Map<String, Object>) frame.build().stream()
                .filter(part -> part.getMediaType() != null
                        && MediaType.APPLICATION_JSON.includes(part.getMediaType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the frame carries no application/json payload"))
                .getData();
    }

    @Test
    void aToolCallReachesTheRunsStreamAsToolCallWithTheContractsPayload() {
        RunStreamRegistry registry = new RunStreamRegistry();
        RecordingStream stream = new RecordingStream();
        registry.register(RUN, stream);
        adapter.attachStreamRegistry(registry);

        assertThat(adapter.record(RUN, event("tool.call", TOOL_CALL_OBJECT_INPUT))).isTrue();

        assertThat(stream.frames).as("one frame per core tool.call").hasSize(1);
        assertThat(rendered(stream.frames.get(0)))
                .as("the exact event name the activity strip dispatches")
                .startsWith("event:tool_call\n");
        assertThat(payloadOf(stream.frames.get(0)))
                .containsOnlyKeys("runId", "name", "args", "toolCallId")
                .containsEntry("runId", RUN.toString())
                .containsEntry("name", "Bash")
                .containsEntry("args", "{\"command\":\"git status\"}")
                .containsEntry("toolCallId", "call-obj");
    }

    @Test
    void aFailedToolResultReachesTheRunsStreamAsOkFalse() {
        RunStreamRegistry registry = new RunStreamRegistry();
        RecordingStream stream = new RecordingStream();
        registry.register(RUN, stream);
        adapter.attachStreamRegistry(registry);

        assertThat(adapter.record(RUN, event("tool.result", TOOL_RESULT_FAILED))).isTrue();

        assertThat(stream.frames).hasSize(1);
        assertThat(rendered(stream.frames.get(0))).startsWith("event:tool_result\n");
        assertThat(payloadOf(stream.frames.get(0)))
                .containsOnlyKeys("runId", "name", "result", "ok", "toolCallId")
                .containsEntry("runId", RUN.toString())
                .containsEntry("name", "Bash")
                .containsEntry("result", "exit code 128")
                .containsEntry("ok", false)
                .containsEntry("toolCallId", "call-obj");
    }

    @Test
    void aSuccessfulToolResultCarriesOkTrue() {
        // The positive control for ok: a result with no isError flag has not failed.
        RunStreamRegistry registry = new RunStreamRegistry();
        RecordingStream stream = new RecordingStream();
        registry.register(RUN, stream);
        adapter.attachStreamRegistry(registry);

        assertThat(adapter.record(RUN, event("tool.result",
                "{\"toolCallId\":\"call-x\",\"toolName\":\"Read\",\"result\":\"ok\"}"))).isTrue();

        assertThat(payloadOf(stream.frames.get(0)))
                .containsEntry("ok", true)
                .containsEntry("result", "ok")
                .containsEntry("name", "Read");
    }

    @Test
    void aRunWithNoRegisteredStreamProducesNoFrameAndNoException() {
        RunStreamRegistry registry = new RunStreamRegistry();
        RecordingStream otherRunsStream = new RecordingStream();
        registry.register(UUID.randomUUID(), otherRunsStream);
        adapter.attachStreamRegistry(registry);

        assertThatCode(() -> {
            assertThat(adapter.record(RUN, event("tool.call", TOOL_CALL_OBJECT_INPUT))).isTrue();
            assertThat(adapter.record(RUN, event("tool.result", TOOL_RESULT_FAILED))).isTrue();
        }).doesNotThrowAnyException();

        // Nothing was invented for a run without a stream, and the other run's stream is untouched.
        assertThat(registry.hasStream(RUN)).isFalse();
        assertThat(otherRunsStream.frames).isEmpty();
    }

    @Test
    void nothingIsStreamedWhenTheTelemetryWriteDidNotSucceed() {
        // The stream must never claim a tool result that was not persisted.
        RunStreamRegistry registry = new RunStreamRegistry();
        RecordingStream stream = new RecordingStream();
        registry.register(RUN, stream);
        adapter.attachStreamRegistry(registry);
        doThrow(new IllegalStateException("database is down"))
                .when(telemetry).recordToolResult(any(), any());

        assertThat(adapter.record(RUN, event("tool.result", TOOL_RESULT_OBJECT))).isFalse();

        assertThat(stream.frames).isEmpty();
    }

    @Test
    void aStreamThatThrowsNeverEscapesTheCoreEventPath() {
        // RunStreamRegistry.emit is documented never to throw, and the core event loop must not
        // depend on that promise to keep a run alive.
        RunStreamRegistry registry = mock(RunStreamRegistry.class);
        when(registry.emit(eq(RUN), any(), any())).thenThrow(new IllegalStateException("stream exploded"));
        adapter.attachStreamRegistry(registry);

        assertThatCode(() -> assertThat(adapter.record(RUN, event("tool.call", TOOL_CALL_OBJECT_INPUT)))
                .as("the write succeeded, so the event is recorded")
                .isTrue()).doesNotThrowAnyException();
        verify(registry).emit(eq(RUN), eq("tool_call"), any());
    }
}
