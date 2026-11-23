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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The phone-facing handler at the unit boundary: {@code hello}/{@code ready}, the
 * subprotocol rule, the hello deadline, and the Task 2 wiring — one bridge per socket,
 * frames routed in both directions, the bridge's exit closing 1011, and the unavailable
 * bridge answered with {@code bridge_internal} + 1011 after {@code ready}.
 */
class LiveWebSocketHandlerTest {

    private static final String HELLO = "{\"type\":\"hello\",\"v\":1,\"conversationId\":\"conv-live\","
            + "\"audio\":{\"inRate\":16000,\"outRate\":24000,\"format\":\"pcm16le\",\"channels\":1},"
            + "\"mic\":true,\"ar\":false}";

    private final ObjectMapper json = new ObjectMapper();
    private final RecordingBridge bridge = new RecordingBridge();
    private final AtomicReference<LiveSession> openedSession = new AtomicReference<>();
    private final LiveBridgeFactory bridges = (live, listener) -> {
        openedSession.set(live);
        bridge.listener = listener;
        return bridge;
    };
    private final CopyOnWriteArrayList<String> toolCalls = new CopyOnWriteArrayList<>();

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

        JsonNode ready = json.readTree(firstText(session));
        assertThat(ready.path("type").asText()).isEqualTo("ready");
        assertThat(ready.path("v").asInt()).isEqualTo(1);
        assertThat(ready.path("model").asText()).isEqualTo("qwen3.8-omni-flash-realtime");
        assertThat(ready.path("modalities").size()).isEqualTo(2);
        assertThat(ready.path("maxSessionSec").asInt()).isEqualTo(7200);
        assertThat(ready.path("sessionId").asText()).isNotBlank();
        assertThat(openedSession.get()).isNotNull();
        assertThat(openedSession.get().id()).isEqualTo(ready.path("sessionId").asText());
        assertThat(openedSession.get().conversationId()).isEqualTo("conv-live");
        assertThat(openedSession.get().runId()).isNull();
        assertThat(openedSession.get().bridge()).isSameAs(bridge);
        assertThat(handler.registry().get(session.getId())).isSameAs(openedSession.get());
        verify(session, never()).close(any());
    }

    @Test
    void theBridgeHelloCarriesTheConversationAndTheSeededContext() throws Exception {
        WebSocketSession session = session("s1", null, null);
        LiveContextSource context = conversationId -> List.of(
                new LiveContextSource.Entry("user", "earlier question"),
                new LiveContextSource.Entry("assistant", "earlier answer"));
        handler = new LiveWebSocketHandler(new LiveProperties(false, null, null, "", 5000, 7200), bridges, json, context);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(HELLO));

        JsonNode hello = json.readTree(bridge.sent.get(0));
        assertThat(hello.path("type").asText()).isEqualTo("hello");
        assertThat(hello.path("v").asInt()).isEqualTo(1);
        assertThat(hello.path("conversationId").asText()).isEqualTo("conv-live");
        assertThat(hello.path("context")).hasSize(2);
        assertThat(hello.path("context").get(0).path("role").asText()).isEqualTo("user");
        assertThat(hello.path("context").get(0).path("text").asText()).isEqualTo("earlier question");
    }

    @Test
    void readyIsSentBeforeTheBridgeIsOpened() throws Exception {
        WebSocketSession session = session("s1", null, null);
        AtomicReference<String> frameAtOpen = new AtomicReference<>();
        LiveBridgeFactory recording = (live, listener) -> {
            try {
                frameAtOpen.set(firstText(session));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return LiveBridge.NOOP;
        };
        handler = new LiveWebSocketHandler(new LiveProperties(false, null, null, "", 5000, 7200), recording, json);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(HELLO));

        assertThat(json.readTree(frameAtOpen.get()).path("type").asText()).isEqualTo("ready");
    }

    @Test
    void anUnavailableBridgeSaysWhyThenCloses1011() throws Exception {
        WebSocketSession session = session("s1", LiveWebSocketHandler.PROTOCOL, LiveWebSocketHandler.PROTOCOL);
        handler = new LiveWebSocketHandler(new LiveProperties(false, null, null, "", 5000, 7200),
                (live, listener) -> {
                    throw new LiveBridgeUnavailableException("live bridge disabled");
                }, json);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(HELLO));

        List<String> payloads = allTexts(session);
        assertThat(json.readTree(payloads.get(0)).path("type").asText()).isEqualTo("ready");
        JsonNode error = json.readTree(payloads.get(payloads.size() - 1));
        assertThat(error.path("type").asText()).isEqualTo("error");
        assertThat(error.path("v").asInt()).isEqualTo(1);
        assertThat(error.path("code").asText()).isEqualTo("bridge_internal");
        assertThat(error.path("retryable").asBoolean()).isTrue();
        assertThat(error.path("message").asText()).isEqualTo("live bridge unavailable");
        verify(session).close(argThat(status -> status.getCode() == 1011));
    }

    @Test
    void aFactoryThatThrowsUnexpectedlyIsStillABridgeInternalClose() throws Exception {
        WebSocketSession session = session("s1", null, null);
        handler = new LiveWebSocketHandler(new LiveProperties(false, null, null, "", 5000, 7200),
                (live, listener) -> {
                    throw new IllegalStateException("process blew up");
                }, json);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(HELLO));

        JsonNode error = json.readTree(allTexts(session).get(1));
        assertThat(error.path("code").asText()).isEqualTo("bridge_internal");
        assertThat(error.path("retryable").asBoolean()).isTrue();
        verify(session).close(argThat(status -> status.getCode() == 1011));
        // The failure reason is logged as a class name only, never a message.
        assertThat(error.path("message").asText()).isEqualTo("live bridge unavailable");
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

            JsonNode error = json.readTree(firstText(session));
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

        assertThat(json.readTree(firstText(session)).path("type").asText()).isEqualTo("ready");
        verify(session).close(argThat(status -> status.getCode() == 4002));
        assertThat(bridge.sent).as("no bridge is spawned for a protocol refusal").isEmpty();
    }

    @Test
    void anOfferedSubprotocolTheServerAcceptedStaysOpen() throws Exception {
        // "aria-live.v1, x" is a valid offer: Spring picks aria-live.v1 out of it
        // and echoes that, so the raw header must not decide the refusal.
        WebSocketSession session = session("s1", "aria-live.v1, x", LiveWebSocketHandler.PROTOCOL);
        handler(5000).afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(HELLO));

        assertThat(json.readTree(firstText(session)).path("type").asText()).isEqualTo("ready");
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
        JsonNode error = json.readTree(firstText(session));
        assertThat(error.path("type").asText()).isEqualTo("error");
        assertThat(error.path("v").asInt()).isEqualTo(1);
        assertThat(error.path("code").asText()).isEqualTo("capability_mismatch");
        assertThat(error.path("retryable").asBoolean(true)).isFalse();
        assertThat(error.path("message").asText()).isEqualTo("hello deadline exceeded");
    }

    @Test
    void phoneTextIsForwardedToTheBridge() throws Exception {
        WebSocketSession session = opened();

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"text\",\"text\":\"next question\"}"));

        assertThat(json.readTree(bridge.sent.get(1)).path("type").asText()).isEqualTo("text");
        assertThat(json.readTree(bridge.sent.get(1)).path("text").asText()).isEqualTo("next question");
    }

    @Test
    void cancelIsForwardedToTheBridge() throws Exception {
        WebSocketSession session = opened();

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"cancel\"}"));

        assertThat(json.readTree(bridge.sent.get(1)).path("type").asText()).isEqualTo("cancel");
    }

    @Test
    void micOffStopsAudioAndMicOnResumesIt() throws Exception {
        WebSocketSession session = opened();
        byte[] pcm = new byte[] {1, 2, 3, 4};

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"mic\",\"on\":false}"));
        handler.handleBinaryMessage(session, new BinaryMessage(ByteBuffer.wrap(pcm)));
        assertThat(json.readTree(bridge.sent.get(1)).path("type").asText()).isEqualTo("mic");
        assertThat(json.readTree(bridge.sent.get(1)).path("on").asBoolean()).isFalse();
        assertThat(bridge.binaries).isEmpty();

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"mic\",\"on\":true}"));
        handler.handleBinaryMessage(session, new BinaryMessage(ByteBuffer.wrap(pcm)));
        assertThat(bridge.binaries).containsExactly(pcm);
    }

    @Test
    void aPhotoFrameIsSentToTheBridgeWithTheCommit() throws Exception {
        WebSocketSession session = opened();
        String base64 = Base64.getEncoder().encodeToString("jpeg-bytes".getBytes());
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"frame\",\"mime\":\"image/jpeg\","
                + "\"dataBase64\":\"" + base64 + "\",\"capturedAtMs\":1}"));

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"commit\",\"image\":true}"));

        assertThat(bridge.sent).hasSize(3);
        assertThat(json.readTree(bridge.sent.get(1)).path("type").asText()).isEqualTo("image");
        assertThat(json.readTree(bridge.sent.get(1)).path("dataBase64").asText()).isEqualTo(base64);
        assertThat(json.readTree(bridge.sent.get(2)).path("type").asText()).isEqualTo("commit");
        assertThat(json.readTree(bridge.sent.get(2)).path("image").asBoolean()).isTrue();
    }

    @Test
    void aCommitWithNoFrameIsSentWithoutAnImage() throws Exception {
        WebSocketSession session = opened();

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"commit\",\"image\":true}"));

        assertThat(json.readTree(bridge.sent.get(1)).path("type").asText()).isEqualTo("commit");
        assertThat(json.readTree(bridge.sent.get(1)).path("image").asBoolean()).isFalse();
    }

    @Test
    void bridgeAudioBecomesAPhoneBinaryFrame() throws Exception {
        WebSocketSession session = opened();
        byte[] pcm = new byte[] {9, 8, 7};

        bridge.listener.onAudio(pcm);

        ArgumentCaptor<BinaryMessage> sent = ArgumentCaptor.forClass(BinaryMessage.class);
        verify(session).sendMessage(sent.capture());
        byte[] payload = new byte[sent.getValue().getPayloadLength()];
        sent.getValue().getPayload().duplicate().get(payload);
        assertThat(payload).isEqualTo(pcm);
    }

    @Test
    void bridgeControlBecomesAPhoneTextFrame() throws Exception {
        WebSocketSession session = opened();

        bridge.listener.onControl("{\"type\":\"speech_end\",\"tMs\":1}");

        assertThat(allTexts(session)).contains("{\"type\":\"speech_end\",\"tMs\":1}");
    }

    @Test
    void aBridgeToolCallIsHandedToTheListenerHook() throws Exception {
        WebSocketSession session = opened();

        bridge.listener.onToolCall("{\"type\":\"tool_call\",\"id\":\"c1\",\"name\":\"get_time\"}");

        assertThat(toolCalls).hasSize(1);
        assertThat(toolCalls.get(0)).contains("\"get_time\"");
        verify(session, never()).close(any());
    }

    @Test
    void anUnexpectedBridgeExitSaysWhyThenCloses1011() throws Exception {
        WebSocketSession session = opened();

        bridge.listener.onExit(9);

        JsonNode error = json.readTree(allTexts(session).get(allTexts(session).size() - 1));
        assertThat(error.path("code").asText()).isEqualTo("bridge_internal");
        assertThat(error.path("retryable").asBoolean()).isTrue();
        verify(session).close(argThat(status -> status.getCode() == 1011));
    }

    @Test
    void aBridgeExitAfterThePhoneClosedIsSilent() throws Exception {
        WebSocketSession session = opened();

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        bridge.listener.onExit(0);

        // The only frame the phone ever saw was ready: a close is not an exit.
        assertThat(allTexts(session)).hasSize(1);
    }

    @Test
    void anUnknownPhoneFrameTypeIsDroppedWithoutClosing() throws Exception {
        WebSocketSession session = opened();

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"nonsense\"}"));

        assertThat(bridge.sent).hasSize(1); // the bridge hello only
        verify(session, never()).close(any());
    }

    @Test
    void unparseableJsonAfterHelloIsDroppedWithoutClosing() throws Exception {
        WebSocketSession session = opened();

        handler.handleTextMessage(session, new TextMessage("not json at all"));

        verify(session, never()).close(any());
        assertThat(bridge.sent).hasSize(1);
    }

    @Test
    void aLegal256KibFrameReachesTheBridgeBeforeItsCommit() throws Exception {
        WebSocketSession session = opened();
        // 196608 bytes of base64 is exactly the 262144-character limit the validator allows.
        String base64 = Base64.getEncoder().encodeToString(new byte[196_608]);
        assertThat(base64).hasSize(LiveFrameValidator.MAX_BASE64_LENGTH);

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"frame\",\"mime\":\"image/jpeg\","
                + "\"dataBase64\":\"" + base64 + "\",\"capturedAtMs\":1}"));
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"commit\",\"image\":true}"));

        assertThat(bridge.sent).hasSize(3);
        assertThat(json.readTree(bridge.sent.get(1)).path("type").asText()).isEqualTo("image");
        assertThat(json.readTree(bridge.sent.get(1)).path("dataBase64").asText()).isEqualTo(base64);
        assertThat(json.readTree(bridge.sent.get(2)).path("type").asText()).isEqualTo("commit");
        assertThat(json.readTree(bridge.sent.get(2)).path("image").asBoolean()).isTrue();
        verify(session, never()).close(any());
    }

    @Test
    void aFrameMessageAboveTheEnvelopeCeilingIsRefusedWithFrameTooLarge() throws Exception {
        WebSocketSession session = opened();

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"frame\",\"mime\":\"image/jpeg\","
                + "\"dataBase64\":\"" + "A".repeat(400 * 1024) + "\"}"));

        // Refused before the validator, but answered: no image line, no close, and an
        // error the phone can act on instead of a silent drop.
        JsonNode error = json.readTree(allTexts(session).get(allTexts(session).size() - 1));
        assertThat(error.path("type").asText()).isEqualTo("error");
        assertThat(error.path("code").asText()).isEqualTo("frame_too_large");
        assertThat(error.path("retryable").asBoolean(true)).isFalse();
        assertThat(error.path("message").asText()).isEqualTo("frame exceeds 256 KiB base64");
        assertThat(bridge.sent).hasSize(1);
        verify(session, never()).close(any());
    }

    @Test
    void aContextSourceFailureStillSaysHelloWithAnEmptyContext() throws Exception {
        WebSocketSession session = session("s1", null, null);
        LiveContextSource failing = conversationId -> {
            throw new IllegalStateException("no database");
        };
        handler = new LiveWebSocketHandler(new LiveProperties(false, null, null, "", 5000, 7200),
                bridges, json, failing);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(HELLO));

        JsonNode hello = json.readTree(bridge.sent.get(0));
        assertThat(hello.path("type").asText()).isEqualTo("hello");
        assertThat(hello.path("context")).isEmpty();
    }

    @Test
    void aContextSourceThatAnswersNullSeedsAnEmptyContext() throws Exception {
        WebSocketSession session = session("s1", null, null);
        handler = new LiveWebSocketHandler(new LiveProperties(false, null, null, "", 5000, 7200),
                bridges, json, conversationId -> null);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(HELLO));

        assertThat(json.readTree(bridge.sent.get(0)).path("context")).isEmpty();
    }

    @Test
    void binaryFramesAreIgnoredBeforeTheBridgeExists() throws Exception {
        WebSocketSession session = session("s1", null, null);
        handler(5000).afterConnectionEstablished(session);

        handler.handleBinaryMessage(session, new BinaryMessage(ByteBuffer.wrap(new byte[] {1, 2})));

        assertThat(bridge.binaries).isEmpty();
    }

    @Test
    void binaryFramesForAnUnknownSocketAreIgnored() throws Exception {
        WebSocketSession session = session("unknown", null, null);

        handler(5000).handleBinaryMessage(session, new BinaryMessage(ByteBuffer.wrap(new byte[] {1, 2})));

        verify(session, never()).sendMessage(any());
    }

    @Test
    void aBridgeThatArrivesAfterTheSocketClosedIsReaped() throws Exception {
        // The hello deadline can fire while the child is starting; the socket is already
        // gone, so the child must not outlive it.
        WebSocketSession session = session("s1", null, null);
        RecordingBridge late = new RecordingBridge();
        handler = new LiveWebSocketHandler(new LiveProperties(false, null, null, "", 5000, 7200),
                (live, listener) -> {
                    live.markClosed();
                    return late;
                }, json);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(HELLO));

        assertThat(late.closes.get()).isEqualTo(1);
        assertThat(late.sent).isEmpty();
    }

    @Test
    void closingTheSocketReapsTheBridge() throws Exception {
        WebSocketSession session = opened();

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThat(bridge.closes.get()).isEqualTo(1);
        assertThat(handler.registry().get(session.getId())).isNull();
    }

    @Test
    void aFrameForAnUnknownSocketIsIgnored() throws Exception {
        WebSocketSession session = session("unknown", null, null);

        handler(5000).handleTextMessage(session, new TextMessage(HELLO));

        verify(session, never()).sendMessage(any());
    }

    // --- harness -------------------------------------------------------------------------

    private WebSocketSession opened() {
        WebSocketSession session = session("s1", LiveWebSocketHandler.PROTOCOL, LiveWebSocketHandler.PROTOCOL);
        handler(5000).afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage(HELLO));
        return session;
    }

    private LiveWebSocketHandler handler(long readyDeadlineMs) {
        handler = new LiveWebSocketHandler(new LiveProperties(false, null, null, "", readyDeadlineMs, 7200),
                bridges, json, conversationId -> List.of(), new LiveSessionRegistry(),
                (live, toolCall) -> toolCalls.add(toolCall));
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

    private String firstText(WebSocketSession session) throws Exception {
        return allTexts(session).get(0);
    }

    private List<String> allTexts(WebSocketSession session) throws Exception {
        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(sent.capture());
        List<String> payloads = new ArrayList<>();
        for (TextMessage message : sent.getAllValues()) {
            payloads.add(message.getPayload());
        }
        return payloads;
    }

    /** Everything the handler writes down to the child. */
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
