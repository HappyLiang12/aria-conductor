package io.aria.conductor.aria.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiveWebSocketHandlerTest {

    private static final String HELLO = "{\"type\":\"hello\",\"v\":1,\"conversationId\":\"conv-live\","
            + "\"audio\":{\"inRate\":16000,\"outRate\":24000,\"format\":\"pcm16le\",\"channels\":1},"
            + "\"mic\":true,\"ar\":false}";

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<String> openedFor = new AtomicReference<>();
    private final AtomicInteger bridgesClosed = new AtomicInteger();
    private final LiveBridgeFactory bridges = (sessionId, conversationId) -> {
        openedFor.set(sessionId + "|" + conversationId);
        return bridgesClosed::incrementAndGet;
    };

    private LiveWebSocketHandler handler;

    @AfterEach
    void stopScheduler() {
        if (handler != null) {
            handler.shutdown();
        }
    }

    @Test
    void validHelloIsAnsweredWithReadyAndOpensTheBridge() throws Exception {
        WebSocketSession session = session("s1", LiveWebSocketHandler.PROTOCOL, LiveWebSocketHandler.PROTOCOL);
        handler(5000).afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(HELLO));

        JsonNode ready = json.readTree(sentPayload(session));
        assertThat(ready.path("type").asText()).isEqualTo("ready");
        assertThat(ready.path("v").asInt()).isEqualTo(1);
        assertThat(ready.path("model").asText()).isEqualTo("qwen3.8-omni-flash-realtime");
        assertThat(ready.path("modalities").size()).isEqualTo(2);
        assertThat(ready.path("maxSessionSec").asInt()).isEqualTo(7200);
        assertThat(ready.path("sessionId").asText()).isNotBlank();
        assertThat(openedFor.get()).isEqualTo(ready.path("sessionId").asText() + "|conv-live");
        verify(session, never()).close(any());
    }

    @Test
    void readyIsSentBeforeTheBridgeIsOpened() throws Exception {
        WebSocketSession session = session("s1", null, null);
        AtomicReference<String> frameAtOpen = new AtomicReference<>();
        LiveBridgeFactory recording = (sessionId, conversationId) -> {
            try {
                frameAtOpen.set(sentPayload(session));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return LiveBridge.NOOP;
        };
        handler = new LiveWebSocketHandler(new LiveProperties(false, null, null, 5000, 7200), recording, json);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(HELLO));

        assertThat(json.readTree(frameAtOpen.get()).path("type").asText()).isEqualTo("ready");
    }

    @Test
    void everyInvalidHelloIsRefusedWithCapabilityMismatchThen4002() throws Exception {
        String[] invalids = {
                HELLO.replace("\"v\":1", "\"v\":2"),
                HELLO.replace("\"conversationId\":\"conv-live\"", "\"conversationId\":\" \""),
                HELLO.replace("\"conversationId\":\"conv-live\"",
                        "\"conversationId\":\"" + "c".repeat(37) + "\""),
                HELLO.replace("\"inRate\":16000", "\"inRate\":8000"),
                HELLO.replace("\"outRate\":24000", "\"outRate\":16000"),
                HELLO.replace("\"format\":\"pcm16le\"", "\"format\":\"f32le\""),
                HELLO.replace("\"channels\":1", "\"channels\":2"),
                "{\"type\":\"hello\"}",
        };

        for (String invalid : invalids) {
            WebSocketSession session = session("s", null, null);
            handler(5000).afterConnectionEstablished(session);

            handler.handleTextMessage(session, new TextMessage(invalid));

            JsonNode error = json.readTree(sentPayload(session));
            assertThat(error.path("type").asText()).as(invalid).isEqualTo("error");
            assertThat(error.path("code").asText()).as(invalid).isEqualTo("capability_mismatch");
            assertThat(error.path("retryable").asBoolean(true)).as(invalid).isFalse();
            assertThat(error.path("message").asText()).as(invalid).isEqualTo("hello rejected");
            verify(session).close(argThat(status -> status.getCode() == 4002));
            handler.shutdown();
        }
    }

    @Test
    void aSecondHelloIsRefused() throws Exception {
        WebSocketSession session = session("s1", null, null);
        handler(5000).afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage(HELLO));

        handler.handleTextMessage(session, new TextMessage(HELLO));

        verify(session).close(argThat(status -> status.getCode() == 4002));
    }

    @Test
    void aNonHelloFrameBeforeHelloIsRefused() throws Exception {
        WebSocketSession session = session("s1", null, null);
        handler(5000).afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"text\",\"text\":\"hi\"}"));

        verify(session).close(argThat(status -> status.getCode() == 4002));
    }

    @Test
    void unparseableJsonIsRefused() throws Exception {
        WebSocketSession session = session("s1", null, null);
        handler(5000).afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage("not json"));

        verify(session).close(argThat(status -> status.getCode() == 4002));
    }

    @Test
    void aWrongOfferedSubprotocolGetsReadyThen4002() throws Exception {
        // Spring echoes only a supported protocol, so an offer it cannot honour
        // leaves the session with no accepted protocol at all.
        WebSocketSession session = session("s1", "aria-live.v2", null);
        handler(5000).afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(HELLO));

        assertThat(json.readTree(sentPayload(session)).path("type").asText()).isEqualTo("ready");
        verify(session).close(argThat(status -> status.getCode() == 4002));
    }

    @Test
    void anOfferedSubprotocolTheServerAcceptedStaysOpen() throws Exception {
        // "aria-live.v1, x" is a valid offer: Spring picks aria-live.v1 out of it
        // and echoes that, so the raw header must not decide the refusal.
        WebSocketSession session = session("s1", "aria-live.v1, x", LiveWebSocketHandler.PROTOCOL);
        handler(5000).afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(HELLO));

        assertThat(json.readTree(sentPayload(session)).path("type").asText()).isEqualTo("ready");
        verify(session, never()).close(any());
    }

    @Test
    void anOfferTheServerCouldNotHonourClosesEvenIfItLooksLikeTheProtocol() throws Exception {
        WebSocketSession session = session("s1", LiveWebSocketHandler.PROTOCOL + "x", null);
        handler(5000).afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(HELLO));

        verify(session).close(argThat(status -> status.getCode() == 4002));
    }

    @Test
    void aSocketThatStaysSilentSaysWhyThenClosesAtTheDeadline() throws Exception {
        WebSocketSession session = session("s1", LiveWebSocketHandler.PROTOCOL, LiveWebSocketHandler.PROTOCOL);

        handler(50).afterConnectionEstablished(session);

        verify(session, timeout(2000)).close(argThat(status -> status.getCode() == 4002));
        JsonNode error = json.readTree(sentPayload(session));
        assertThat(error.path("type").asText()).isEqualTo("error");
        assertThat(error.path("v").asInt()).isEqualTo(1);
        assertThat(error.path("code").asText()).isEqualTo("capability_mismatch");
        assertThat(error.path("retryable").asBoolean(true)).isFalse();
        assertThat(error.path("message").asText()).isEqualTo("hello deadline exceeded");
    }

    @Test
    void aBinaryFrameIsDroppedWithoutClosing() throws Exception {
        WebSocketSession session = session("s1", null, null);
        handler(5000).afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage(HELLO));

        handler.handleBinaryMessage(session, new BinaryMessage(ByteBuffer.allocate(4)));

        verify(session, never()).close(any());
    }

    @Test
    void closingTheSocketReapsTheBridge() throws Exception {
        WebSocketSession session = session("s1", null, null);
        handler(5000).afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage(HELLO));

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThat(bridgesClosed.get()).isEqualTo(1);
    }

    @Test
    void aFrameForAnUnknownSocketIsIgnored() throws Exception {
        WebSocketSession session = session("unknown", null, null);

        handler(5000).handleTextMessage(session, new TextMessage(HELLO));

        verify(session, never()).sendMessage(any());
    }

    private LiveWebSocketHandler handler(long readyDeadlineMs) {
        handler = new LiveWebSocketHandler(
                new LiveProperties(false, null, null, readyDeadlineMs, 7200), bridges, json);
        return handler;
    }

    private static WebSocketSession session(String id, String offeredProtocol, String acceptedProtocol) {
        Map<String, Object> attributes = new HashMap<>();
        if (offeredProtocol != null) {
            attributes.put(LiveHandshakeInterceptor.OFFERED_PROTOCOL_ATTRIBUTE, offeredProtocol);
        }
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.getAcceptedProtocol()).thenReturn(acceptedProtocol);
        return session;
    }

    private String sentPayload(WebSocketSession session) throws Exception {
        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(sent.capture());
        return sent.getValue().getPayload();
    }
}
