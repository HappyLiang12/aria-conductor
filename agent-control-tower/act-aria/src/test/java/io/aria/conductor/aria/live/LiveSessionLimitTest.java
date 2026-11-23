package io.aria.conductor.aria.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spec limits: text frames above 64 KiB are dropped, binary frames above 32 KiB are
 * dropped, and an image frame over 256 KiB of base64 or with a wrong mime is answered
 * with {@code frame_too_large} / {@code frame_invalid} while the socket stays open.
 * A second {@code hello} still closes 4002.
 */
class LiveSessionLimitTest {

    private static final String HELLO = "{\"type\":\"hello\",\"v\":1,\"conversationId\":\"conv-live\","
            + "\"audio\":{\"inRate\":16000,\"outRate\":24000,\"format\":\"pcm16le\",\"channels\":1},"
            + "\"mic\":true,\"ar\":false}";

    private final ObjectMapper json = new ObjectMapper();
    private final RecordingBridge bridge = new RecordingBridge();
    private LiveWebSocketHandler handler;

    @AfterEach
    void stopScheduler() {
        if (handler != null) {
            handler.shutdown();
        }
    }

    @Test
    void aTextFrameAboveSixtyFourKibIsDroppedWithoutClosing() throws Exception {
        WebSocketSession session = opened();

        handler.handleTextMessage(session, new TextMessage(
                "{\"type\":\"text\",\"text\":\"" + "a".repeat(70_000) + "\"}"));

        assertThat(bridge.sent).hasSize(1); // the bridge hello only
        assertThat(bridge.sent.get(0)).contains("\"type\":\"hello\"");
        verify(session, never()).close(any());
    }

    @Test
    void aBinaryFrameAboveThirtyTwoKibIsDroppedWithoutClosing() throws Exception {
        WebSocketSession session = opened();

        handler.handleBinaryMessage(session, new BinaryMessage(ByteBuffer.allocate(33 * 1024)));

        assertThat(bridge.binaries).isEmpty();
        verify(session, never()).close(any());
    }

    @Test
    void aFrameAboveTheBase64LimitIsAnsweredTooLargeAndKeepsTheSocket() throws Exception {
        WebSocketSession session = opened();

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"frame\",\"mime\":\"image/jpeg\","
                + "\"dataBase64\":\"" + "A".repeat(LiveFrameValidator.MAX_BASE64_LENGTH + 1) + "\"}"));

        JsonNode error = lastPhoneFrame(session);
        assertThat(error.path("type").asText()).isEqualTo("error");
        assertThat(error.path("code").asText()).isEqualTo("frame_too_large");
        assertThat(error.path("retryable").asBoolean()).isTrue();
        assertThat(bridge.sent).hasSize(1); // no image line left for the bridge
        verify(session, never()).close(any());
    }

    @Test
    void aFrameThatIsNotJpegIsAnsweredInvalidAndKeepsTheSocket() throws Exception {
        WebSocketSession session = opened();
        String base64 = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, 4});

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"frame\",\"mime\":\"image/png\","
                + "\"dataBase64\":\"" + base64 + "\"}"));

        JsonNode error = lastPhoneFrame(session);
        assertThat(error.path("code").asText()).isEqualTo("frame_invalid");
        assertThat(bridge.sent).hasSize(1);
        verify(session, never()).close(any());
    }

    @Test
    void aFrameEnvelopeAboveTheHandlerCeilingIsRefusedAndKeepsTheSocket() throws Exception {
        WebSocketSession session = opened();

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"frame\",\"mime\":\"image/jpeg\","
                + "\"dataBase64\":\"" + "A".repeat(400 * 1024) + "\"}"));

        // Too large for the envelope, so it never reaches the validator: the phone is
        // told to shrink it instead of being left waiting for a reply that never comes.
        JsonNode error = lastPhoneFrame(session);
        assertThat(error.path("type").asText()).isEqualTo("error");
        assertThat(error.path("v").asInt()).isEqualTo(1);
        assertThat(error.path("code").asText()).isEqualTo("frame_too_large");
        assertThat(error.path("retryable").asBoolean(true)).isFalse();
        assertThat(error.path("message").asText()).isEqualTo("frame exceeds 256 KiB base64");
        assertThat(bridge.sent).hasSize(1); // no image line left for the bridge
        verify(session, never()).close(any());
    }

    @Test
    void aDuplicateTypeKeyCannotSmuggleAnOversizedMessagePastTheTextCap() throws Exception {
        WebSocketSession session = opened();

        // The streaming probe reads the first "type" key, Jackson keeps the last, so a
        // message that claims frame first and text last is judged by what the parse yields.
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"frame\",\"type\":\"text\","
                + "\"text\":\"" + "a".repeat(300 * 1024) + "\"}"));

        assertThat(bridge.sent).hasSize(1); // the bridge hello only
        assertThat(lastPhoneFrame(session).path("type").asText()).isEqualTo("ready");
        verify(session, never()).close(any());
    }

    @Test
    void aSecondHelloCloses4002() throws Exception {
        WebSocketSession session = opened();

        handler.handleTextMessage(session, new TextMessage(HELLO));

        verify(session).close(org.mockito.ArgumentMatchers.argThat(status -> status.getCode() == 4002));
    }

    // --- harness -------------------------------------------------------------------------

    private WebSocketSession opened() {
        WebSocketSession session = session("s1");
        handler(5000).afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage(HELLO));
        return session;
    }

    private LiveWebSocketHandler handler(long readyDeadlineMs) {
        LiveBridgeFactory factory = (live, listener) -> {
            bridge.listener = listener;
            return bridge;
        };
        handler = new LiveWebSocketHandler(
                new LiveProperties(false, null, null, "", readyDeadlineMs, 7200), factory, json);
        return handler;
    }

    private JsonNode lastPhoneFrame(WebSocketSession session) throws Exception {
        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(sent.capture());
        List<String> payloads = new ArrayList<>();
        for (TextMessage message : sent.getAllValues()) {
            payloads.add(message.getPayload());
        }
        return json.readTree(payloads.get(payloads.size() - 1));
    }

    private static WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.getAttributes()).thenReturn(new HashMap<>(Map.of(
                LiveHandshakeInterceptor.OFFERED_PROTOCOL_ATTRIBUTE, LiveWebSocketHandler.PROTOCOL)));
        when(session.getAcceptedProtocol()).thenReturn(LiveWebSocketHandler.PROTOCOL);
        return session;
    }

    /** Everything the handler sends down to the child. */
    private static final class RecordingBridge implements LiveBridge {

        final List<String> sent = new CopyOnWriteArrayList<>();
        final List<byte[]> binaries = new CopyOnWriteArrayList<>();
        final AtomicInteger closes = new AtomicInteger();
        volatile LiveBridgeListener listener;

        @Override
        public void send(String json) {
            sent.add(json);
        }

        @Override
        public void sendBinary(byte[] pcm) {
            binaries.add(pcm);
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }
}
