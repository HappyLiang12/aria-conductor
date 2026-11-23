package io.aria.conductor.aria.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.model.RunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A bridge {@code tool_call} at the socket seam (spec X7, L4): the turn's lazy run starts
 * before the tool runs, the bridge always gets its {@code tool_result} back, and the phone
 * sees the chips change. A refused call carries a stable sentence — never a parser's or a
 * service's exception text — and a gated call parks the run instead of answering.
 */
class LiveToolCallHandlerTest {

    private static final String CALL = "{\"type\":\"tool_call\",\"id\":\"c1\",\"name\":\"get_time\",\"arguments\":{}}";

    private final ObjectMapper json = new ObjectMapper();
    private final LiveFrames frames = new LiveFrames(json);
    private final LiveRunService runs = mock(LiveRunService.class);
    private final LiveToolExecutor tools = mock(LiveToolExecutor.class);
    private final LiveEventRouter router = mock(LiveEventRouter.class);
    private final LiveToolCallHandler handler = new LiveToolCallHandler(runs, tools, frames, json, router);

    private final WebSocketSession ws = mock(WebSocketSession.class);
    private final RecordingBridge bridge = new RecordingBridge();
    private final LiveSession session = new LiveSession("s1", "conv-live", ws, null);
    private final UUID runId = UUID.randomUUID();
    private final UUID approvalId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        session.bridge(bridge);
    }

    @Test
    void aToolCallStartsTheTurnsRunAndAnswersTheBridge() throws Exception {
        when(runs.findOrCreateForToolTurn(session, "get_time")).thenReturn(runId);
        when(tools.execute(eq("get_time"), any())).thenReturn(new LiveToolExecutor.ToolOutcome.Value("{\"now\":\"t\"}"));

        handler.onToolCall(session, CALL);

        List<JsonNode> phone = phone();
        assertThat(phone).anyMatch(frame -> frame.path("type").asText().equals("run_event")
                && frame.path("runId").asText().equals(runId.toString())
                && frame.path("state").asText().equals("started"));
        assertThat(phone).anyMatch(frame -> frame.path("type").asText().equals("tool_status")
                && frame.path("status").asText().equals("running"));
        assertThat(phone).anyMatch(frame -> frame.path("type").asText().equals("tool_status")
                && frame.path("status").asText().equals("done"));
        assertThat(phone).anyMatch(frame -> frame.path("type").asText().equals("run_event")
                && frame.path("state").asText().equals("progress"));
        assertThat(bridge.sent).singleElement().asString()
                .contains("\"type\":\"tool_result\"").contains("\"id\":\"c1\"")
                .contains("\"ok\":true").contains("{\\\"now\\\":\\\"t\\\"}");
    }

    @Test
    void aRefusedToolAnswersWithTheStableSentenceOnly() {
        when(runs.findOrCreateForToolTurn(session, "get_time")).thenReturn(runId);
        when(tools.execute(eq("get_time"), any()))
                .thenReturn(new LiveToolExecutor.ToolOutcome.Failure("Missing required argument 'id'"));

        handler.onToolCall(session, CALL);

        assertThat(bridge.sent).singleElement().asString()
                .contains("\"ok\":false")
                .contains("Missing required argument 'id'");
        assertThat(phone()).noneMatch(frame -> frame.toString().contains("Missing required argument"));
    }

    @Test
    void anUnreadableLineIsAnsweredAndCreatesNoRun() {
        handler.onToolCall(session, "not a tool call");

        assertThat(bridge.sent).singleElement().asString()
                .contains("\"ok\":false").contains(LiveToolCallHandler.UNREADABLE);
        verifyNoInteractions(runs);
        verifyNoInteractions(tools);
    }

    @Test
    void aCallWithoutAToolNameCreatesNoRun() {
        handler.onToolCall(session, "{\"type\":\"tool_call\",\"id\":\"c1\"}");

        assertThat(bridge.sent).singleElement().asString()
                .contains("\"ok\":false").contains(LiveToolCallHandler.UNKNOWN_TOOL);
        verifyNoInteractions(runs);
    }

    @Test
    void aGatedToolParksTheRunInsteadOfAnsweringIt() throws Exception {
        JsonNode arguments = json.readTree("{\"id\":\"r1\"}");
        when(runs.findOrCreateForToolTurn(session, "get_run")).thenReturn(runId);
        when(tools.execute(eq("get_run"), any())).thenReturn(new LiveToolExecutor.ToolOutcome.PendingApproval(
                runId, approvalId, "get_run", "WRITE"));

        handler.onToolCall(session, "{\"type\":\"tool_call\",\"id\":\"c1\",\"name\":\"get_run\","
                + "\"arguments\":{\"id\":\"r1\"}}");

        verify(router).parkForApproval(eq(session), eq(runId), eq(approvalId), eq("get_run"),
                eq("WRITE"), eq("c1"), eq(arguments));
        assertThat(bridge.sent).isEmpty();
    }

    @Test
    void anOutcomeThatBlowsUpStillAnswersAndFailsTheRun() {
        when(runs.findOrCreateForToolTurn(session, "get_time")).thenReturn(runId);
        when(tools.execute(eq("get_time"), any())).thenThrow(new IllegalStateException("db is down"));

        handler.onToolCall(session, CALL);

        assertThat(bridge.sent).singleElement().asString()
                .contains("\"ok\":false").contains(LiveToolCallHandler.FAILED);
        verify(runs).settle(eq(session), eq(runId), eq(RunStatus.FAILED), eq(LiveToolCallHandler.FAILED));
        assertThat(phone()).noneMatch(frame -> frame.toString().contains("db is down"));
    }

    @Test
    void theTurnEndAndTheSocketCloseAreTheRoutersBusiness() {
        handler.onTurnDone(session);
        handler.onClosed(session);

        verify(router).turnDone(session);
        verify(router).socketClosed(session);
        verify(runs, never()).settle(any(), any(), any(), isNull());
    }

    // --- harness ---------------------------------------------------------------------------

    private List<JsonNode> phone() {
        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        try {
            verify(ws, atLeast(0)).sendMessage(sent.capture());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        List<JsonNode> frames = new ArrayList<>();
        for (TextMessage message : sent.getAllValues()) {
            try {
                frames.add(json.readTree(message.getPayload()));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return frames;
    }

    private static final class RecordingBridge implements LiveBridge {

        private final List<String> sent = new ArrayList<>();

        @Override
        public void send(String json) {
            sent.add(json);
        }

        @Override
        public void sendBinary(byte[] pcm) {
        }

        @Override
        public void close() {
        }
    }
}
