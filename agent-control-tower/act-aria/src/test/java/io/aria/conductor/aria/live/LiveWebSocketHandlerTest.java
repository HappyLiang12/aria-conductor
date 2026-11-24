package io.aria.conductor.aria.live;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
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
    /** A phone that declared its AR mode before hello: the field is simply absent. */
    private static final String HELLO_NO_AR = HELLO.replace(",\"ar\":false", "");
    private static final String HELLO_AR = HELLO.replace("\"ar\":false", "\"ar\":true");

    private final ObjectMapper json = new ObjectMapper();
    private final RecordingBridge bridge = new RecordingBridge();
    private final AtomicReference<LiveSession> openedSession = new AtomicReference<>();
    private final LiveBridgeFactory bridges = (live, listener) -> {
        openedSession.set(live);
        bridge.listener = listener;
        return bridge;
    };
    private final CopyOnWriteArrayList<String> toolCalls = new CopyOnWriteArrayList<>();
    private final AtomicInteger turnEnds = new AtomicInteger();
    private final AtomicInteger sinkCloses = new AtomicInteger();
    /** The direct backup locator (spec D6), stubbed per test. */
    private final LiveLocator locator = mock(LiveLocator.class);
    /** The Task 4 sink: records every signal the handler must hand over. */
    private final LiveToolCallSink sink = new LiveToolCallSink() {
        @Override
        public void onToolCall(LiveSession live, String json) {
            toolCalls.add(json);
        }

        @Override
        public void onTurnDone(LiveSession live) {
            turnEnds.incrementAndGet();
        }

        @Override
        public void onClosed(LiveSession live) {
            sinkCloses.incrementAndGet();
        }
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
        handler = new LiveWebSocketHandler(new LiveProperties(false, null, null, "", 5000, 7200, false, null, null, 0), bridges, json, context);
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
        handler = new LiveWebSocketHandler(new LiveProperties(false, null, null, "", 5000, 7200, false, null, null, 0), recording, json);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(HELLO));

        assertThat(json.readTree(frameAtOpen.get()).path("type").asText()).isEqualTo("ready");
    }

    @Test
    void anUnavailableBridgeSaysWhyThenCloses1011() throws Exception {
        WebSocketSession session = session("s1", LiveWebSocketHandler.PROTOCOL, LiveWebSocketHandler.PROTOCOL);
        handler = new LiveWebSocketHandler(new LiveProperties(false, null, null, "", 5000, 7200, false, null, null, 0),
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
        handler = new LiveWebSocketHandler(new LiveProperties(false, null, null, "", 5000, 7200, false, null, null, 0),
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
    void aMicFrameBeforeHelloIsIgnoredRatherThanRefused() throws Exception {
        WebSocketSession session = session("s1", null, null);
        handler = handler(5000);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"mic\",\"v\":1,\"on\":true}"));

        verify(session, never()).sendMessage(any(TextMessage.class));

        handler.handleTextMessage(session, new TextMessage(HELLO));

        assertThat(json.readTree(allTexts(session).get(0)).path("type").asText()).isEqualTo("ready");
        verify(session, never()).close(any());
    }

    @Test
    void anArFrameBeforeHelloIsRememberedAndCarriedInTheBridgeHello() throws Exception {
        WebSocketSession session = session("s1", null, null);
        handler = handler(5000);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"ar\",\"v\":1,\"on\":true}"));

        // Nothing to answer and nothing to refuse: there is no bridge yet. A malformed one
        // is ignored on this path too rather than closing the socket before its hello.
        verify(session, never()).sendMessage(any(TextMessage.class));
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"ar\",\"v\":1}"));
        verify(session, never()).sendMessage(any(TextMessage.class));

        // A hello without its own ar keeps the mode the phone declared first.
        handler.handleTextMessage(session, new TextMessage(HELLO_NO_AR));

        assertThat(json.readTree(allTexts(session).get(0)).path("type").asText()).isEqualTo("ready");
        JsonNode hello = json.readTree(bridge.sent.get(0));
        assertThat(hello.path("type").asText()).isEqualTo("hello");
        assertThat(hello.path("ar").asBoolean()).isTrue();
        verify(session, never()).close(any());
    }

    @Test
    void helloArSeedsTheBridgeHello() throws Exception {
        WebSocketSession off = session("s1", null, null);
        handler(5000).afterConnectionEstablished(off);
        handler.handleTextMessage(off, new TextMessage(HELLO));
        assertThat(json.readTree(bridge.sent.get(0)).path("ar").asBoolean(true)).isFalse();

        handler.shutdown();
        WebSocketSession on = session("s2", null, null);
        handler(5000).afterConnectionEstablished(on);
        handler.handleTextMessage(on, new TextMessage(HELLO_AR));
        assertThat(json.readTree(bridge.sent.get(1)).path("ar").asBoolean()).isTrue();
    }

    @Test
    void helloArCommitIsEchoedInReadyAndSeedsTheBridgeHello() throws Exception {
        WebSocketSession session = session("s1", null, null);
        handler = handler(5000);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session,
                new TextMessage(HELLO_AR.replace("}", ",\"arCommit\":true}")));

        // The phone waits for this acknowledgement before its first AR look: the bridge is
        // started in manual mode, so the first look is a normal AR turn (D5 amendment).
        JsonNode ready = json.readTree(allTexts(session).get(0));
        assertThat(ready.path("type").asText()).isEqualTo("ready");
        assertThat(ready.path("arCommit").asBoolean()).isTrue();

        JsonNode hello = json.readTree(bridge.sent.get(0));
        assertThat(hello.path("type").asText()).isEqualTo("hello");
        assertThat(hello.path("ar").asBoolean()).isTrue();
        assertThat(hello.path("arCommit").asBoolean()).isTrue();
        verify(session, never()).close(any());
    }

    @Test
    void aHelloWithoutArCommitKeepsTheGuardAndSaysSoInReady() throws Exception {
        WebSocketSession session = session("s1", null, null);
        handler = handler(5000);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(HELLO_AR));

        JsonNode ready = json.readTree(allTexts(session).get(0));
        assertThat(ready.path("arCommit").asBoolean()).isFalse();
        assertThat(json.readTree(bridge.sent.get(0)).path("arCommit").asBoolean()).isFalse();
    }

    @Test
    void anArFrameAfterHelloIsForwardedToTheBridge() throws Exception {
        WebSocketSession session = opened();

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"ar\",\"v\":1,\"on\":true}"));

        assertThat(bridge.sent).hasSize(2);
        JsonNode on = json.readTree(bridge.sent.get(1));
        assertThat(on.path("type").asText()).isEqualTo("ar");
        assertThat(on.path("on").asBoolean()).isTrue();
        assertThat(openedSession.get().arOn()).isTrue();

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"ar\",\"v\":1,\"on\":false}"));

        JsonNode off = json.readTree(bridge.sent.get(2));
        assertThat(off.path("type").asText()).isEqualTo("ar");
        assertThat(off.path("on").asBoolean(true)).isFalse();
        assertThat(openedSession.get().arOn()).isFalse();
        verify(session, never()).close(any());
    }

    @Test
    void anInvalidArFrameIsIgnoredWithoutClosingOrAnswering() throws Exception {
        WebSocketSession session = opened();

        // A stale version, no `on`, and a non-boolean `on`: none of them is a mode.
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"ar\",\"v\":2,\"on\":true}"));
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"ar\",\"v\":1}"));
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"ar\",\"v\":1,\"on\":\"yes\"}"));

        assertThat(bridge.sent).hasSize(1); // the bridge hello only
        assertThat(openedSession.get().arOn()).isFalse();
        // A duplicate declaration is the last state winning, not a refusal.
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"ar\",\"v\":1,\"on\":true}"));
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"ar\",\"v\":1,\"on\":true}"));
        assertThat(openedSession.get().arOn()).isTrue();
        verify(session, never()).close(any());
        assertThat(allTexts(session)).noneMatch(text -> text.contains("\"error\""));
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
    void oneFrameWithAudioAndCommitReachesTheBridge() throws Exception {
        WebSocketSession session = opened();
        String base64 = Base64.getEncoder().encodeToString("jpeg-bytes".getBytes());

        handler.handleBinaryMessage(session, new BinaryMessage(ByteBuffer.wrap(new byte[] {1, 2, 3, 4})));
        handler.handleTextMessage(session, new TextMessage(frame(base64)));
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"commit\",\"image\":true}"));

        assertThat(bridge.binaries).hasSize(1);
        assertThat(bridge.sent).hasSize(3);
        assertThat(json.readTree(bridge.sent.get(1)).path("type").asText()).isEqualTo("image");
        assertThat(json.readTree(bridge.sent.get(1)).path("dataBase64").asText()).isEqualTo(base64);
        assertThat(json.readTree(bridge.sent.get(2)).path("type").asText()).isEqualTo("commit");
        assertThat(json.readTree(bridge.sent.get(2)).path("image").asBoolean()).isTrue();
        verify(session, never()).close(any());
    }

    @Test
    void aSecondFrameInTheSameTurnIsRefusedAndTheFirstStands() throws Exception {
        WebSocketSession session = opened();
        String first = Base64.getEncoder().encodeToString("first-jpeg".getBytes());
        String second = Base64.getEncoder().encodeToString("second-jpeg".getBytes());

        handler.handleTextMessage(session, new TextMessage(frame(first)));
        handler.handleTextMessage(session, new TextMessage(frame(second)));

        JsonNode error = json.readTree(lastText(session));
        assertThat(error.path("type").asText()).isEqualTo("error");
        assertThat(error.path("v").asInt()).isEqualTo(1);
        assertThat(error.path("code").asText()).isEqualTo("frame_invalid");
        assertThat(error.path("retryable").asBoolean(true)).isFalse();
        assertThat(error.path("message").asText()).isEqualTo("one frame per turn");
        // Only the bridge hello and the turn's one frame: the second never crossed.
        assertThat(bridge.sent).hasSize(2);
        assertThat(json.readTree(bridge.sent.get(1)).path("dataBase64").asText()).isEqualTo(first);
        verify(session, never()).close(any());
    }

    @Test
    void anImageCommitWithNoAudioSinceTheTurnBoundaryIsRefused() throws Exception {
        WebSocketSession session = opened();

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"commit\",\"image\":true}"));

        JsonNode error = json.readTree(lastText(session));
        assertThat(error.path("type").asText()).isEqualTo("error");
        assertThat(error.path("v").asInt()).isEqualTo(1);
        assertThat(error.path("code").asText()).isEqualTo("frame_invalid");
        assertThat(error.path("retryable").asBoolean(true)).isFalse();
        assertThat(error.path("message").asText()).isEqualTo("image commit requires audio");
        // Neither the commit nor an image line reached the fake bridge.
        assertThat(bridge.sent).hasSize(1);
        verify(session, never()).close(any());
    }

    @Test
    void anImageCommitIsForwardedWithThePhonesOwnFlagBeforeItsFrame() throws Exception {
        WebSocketSession session = opened();

        handler.handleBinaryMessage(session, new BinaryMessage(ByteBuffer.wrap(new byte[] {1, 2, 3, 4})));
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"commit\",\"image\":true}"));

        // Spec D4 AR ordering: the phone commits at speech_end and sends the frame later, so
        // the flag is the phone's to set. Java never rewrites it from what it has seen.
        assertThat(json.readTree(bridge.sent.get(1)).path("type").asText()).isEqualTo("commit");
        assertThat(json.readTree(bridge.sent.get(1)).path("image").asBoolean()).isTrue();
    }

    /**
     * The decided AR order (spec D4): audio, then {@code commit{image:true}} at speech_end,
     * then the frame ~150 ms later. The commit must reach the bridge with its own flag, the
     * frame must be accepted after it, and a second frame in that turn must be refused.
     */
    @Test
    void theArOrderSendsTheImageCommitBeforeTheFrameItNames() throws Exception {
        WebSocketSession session = opened();
        String base64 = Base64.getEncoder().encodeToString("ar-jpeg".getBytes());
        String second = Base64.getEncoder().encodeToString("ar-jpeg-2".getBytes());

        handler.handleBinaryMessage(session, new BinaryMessage(ByteBuffer.wrap(new byte[] {1, 2, 3, 4})));
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"commit\",\"image\":true}"));
        handler.handleTextMessage(session, new TextMessage(frame(base64)));
        handler.handleTextMessage(session, new TextMessage(frame(second)));

        // hello, commit{image:true}, image; the commit precedes the frame it will hold for.
        assertThat(bridge.sent).hasSize(3);
        assertThat(json.readTree(bridge.sent.get(1)).path("type").asText()).isEqualTo("commit");
        assertThat(json.readTree(bridge.sent.get(1)).path("image").asBoolean()).isTrue();
        assertThat(json.readTree(bridge.sent.get(2)).path("type").asText()).isEqualTo("image");
        assertThat(json.readTree(bridge.sent.get(2)).path("dataBase64").asText()).isEqualTo(base64);

        JsonNode error = json.readTree(lastText(session));
        assertThat(error.path("code").asText()).isEqualTo("frame_invalid");
        assertThat(error.path("message").asText()).isEqualTo("one frame per turn");
        assertThat(error.path("retryable").asBoolean(true)).isFalse();
        verify(session, never()).close(any());
    }

    @Test
    void aSpeechEndStartsANewTurnSoTheNextFrameIsAccepted() throws Exception {
        WebSocketSession session = opened();
        String first = Base64.getEncoder().encodeToString("first-jpeg".getBytes());
        String second = Base64.getEncoder().encodeToString("second-jpeg".getBytes());

        handler.handleTextMessage(session, new TextMessage(frame(first)));
        // The bridge's own turn end: the next utterance may carry its own frame.
        bridge.listener.onControl("{\"type\":\"speech_end\",\"tMs\":1}");
        handler.handleTextMessage(session, new TextMessage(frame(second)));

        assertThat(bridge.sent).hasSize(3);
        assertThat(json.readTree(bridge.sent.get(2)).path("type").asText()).isEqualTo("image");
        assertThat(json.readTree(bridge.sent.get(2)).path("dataBase64").asText()).isEqualTo(second);
        verify(session, never()).close(any());
    }

    @Test
    void anImageCommitAfterTheSpeechEndBoundaryStillNeedsFreshAudio() throws Exception {
        WebSocketSession session = opened();
        String base64 = Base64.getEncoder().encodeToString("jpeg-bytes".getBytes());

        handler.handleBinaryMessage(session, new BinaryMessage(ByteBuffer.wrap(new byte[] {1, 2, 3, 4})));
        handler.handleTextMessage(session, new TextMessage(frame(base64)));
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"commit\",\"image\":true}"));
        // The provider's turn end already reset the audio count; a commit at the phone's
        // own speech_end without fresh audio is still the refusal.
        bridge.listener.onControl("{\"type\":\"speech_end\",\"tMs\":1}");
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"commit\",\"image\":true}"));

        assertThat(json.readTree(lastText(session)).path("code").asText()).isEqualTo("frame_invalid");
        assertThat(bridge.sent).hasSize(3);
    }

    @Test
    void aCommitWithoutAnImageIsForwardedUnchanged() throws Exception {
        WebSocketSession session = opened();

        handler.handleBinaryMessage(session, new BinaryMessage(ByteBuffer.wrap(new byte[] {1, 2, 3, 4})));
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"commit\",\"image\":false}"));

        assertThat(bridge.sent).hasSize(2);
        assertThat(json.readTree(bridge.sent.get(1)).path("type").asText()).isEqualTo("commit");
        assertThat(json.readTree(bridge.sent.get(1)).path("image").asBoolean(true)).isFalse();
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

    /**
     * Spec D2: the display tool's audit trace names the render and nothing else. The label,
     * the caption and the box travel to the phone and never into a log line.
     */
    @Test
    void anArRenderIsAuditedByItsRenderIdAndNothingElse() throws Exception {
        WebSocketSession session = opened();
        String render = "{\"type\":\"ar_render\",\"v\":1,\"renderId\":\"3f1c2d5e-0000-4a4a-8a8a-001122334455\","
                + "\"render\":{\"primitive\":\"highlight\",\"target\":{\"label\":\"5mm 六角匙\","
                + "\"bbox\":[0.508,0.586,0.351,0.273]},\"text\":\"5mm 六角匙\",\"ttlMs\":8000,"
                + "\"coord\":\"normalized\",\"safetyAlert\":null}}";
        Logger logger = (Logger) LoggerFactory.getLogger(LiveWebSocketHandler.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> lines = new ListAppender<>();
        lines.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(lines);
        try {
            bridge.listener.onControl(render);
        } finally {
            logger.detachAppender(lines);
            logger.setLevel(previous);
        }

        List<String> audit = lines.list.stream()
                .map(event -> event.getFormattedMessage())
                .filter(message -> message.contains("ar_render"))
                .filter(message -> message.contains("renderId="))
                .toList();
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0)).isEqualTo(
                "aria live -> phone ar_render: renderId=3f1c2d5e-0000-4a4a-8a8a-001122334455");
        assertThat(audit.get(0)).doesNotContain("5mm").doesNotContain("0.508");
        // The render itself still crosses verbatim.
        assertThat(allTexts(session)).contains(render);
    }

    // --- the direct backup locator (spec D6) ----------------------------------------------

    /**
     * The backup's happy path: a look-intent question about a frame the bridge answered
     * without a render gets exactly one render, in the bridge's own shape, after the turn.
     * The phone commits before it sends the frame (the AR order, spec D4), so the frame is
     * stamped to the utterance that asks.
     */
    @Test
    void aLookIntentWithAFrameAndNoRenderGetsOneBackupRender() throws Exception {
        when(locator.locate(anyString(), any())).thenReturn(Optional.of(
                new LiveLocator.Render("5mm 六角匙", "5mm 六角匙", new double[] {0.2, 0.1, 0.2, 0.2})));
        WebSocketSession session = openedWithLocator();
        String question = "睇下個 5mm 六角匙喺邊";
        String transcript = "{\"type\":\"input_transcript\",\"v\":1,\"seq\":3,\"text\":\"" + question
                + "\",\"final\":true}";

        handler.handleBinaryMessage(session, new BinaryMessage(ByteBuffer.wrap(new byte[] {1, 2, 3, 4})));
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"commit\",\"image\":true}"));
        handler.handleTextMessage(session, new TextMessage(frame(Base64.getEncoder().encodeToString("ar-jpeg".getBytes()))));
        bridge.listener.onControl(transcript);
        bridge.listener.onTurnDone();

        JsonNode render = awaitPhoneFrame(session, "ar_render");
        assertThat(render.path("v").asInt()).isEqualTo(1);
        UUID.fromString(render.path("renderId").asText()); // a render id, not a run id
        assertThat(render.has("runId")).as("display-only renders carry no runId").isFalse();
        JsonNode body = render.path("render");
        assertThat(body.path("primitive").asText()).isEqualTo("highlight");
        assertThat(body.path("target").path("label").asText()).isEqualTo("5mm 六角匙");
        JsonNode bbox = body.path("target").path("bbox");
        assertThat(bbox.get(0).asDouble()).isEqualTo(0.2);
        assertThat(bbox.get(1).asDouble()).isEqualTo(0.1);
        assertThat(bbox.get(2).asDouble()).isEqualTo(0.2);
        assertThat(bbox.get(3).asDouble()).isEqualTo(0.2);
        assertThat(body.path("text").asText()).isEqualTo("5mm 六角匙");
        assertThat(body.path("ttlMs").asLong()).isEqualTo(8000L);
        assertThat(body.path("coord").asText()).isEqualTo("normalized");
        assertThat(body.has("safetyAlert")).isTrue();
        assertThat(body.path("safetyAlert").isNull()).isTrue();

        // The question was the turn's own final transcript and its own frame bytes.
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(locator, timeout(1000)).locate(eq(question), bytes.capture());
        assertThat(bytes.getValue()).isEqualTo("ar-jpeg".getBytes());

        // The spoken turn is untouched: its control crossed verbatim and nothing new went
        // down to the bridge (hello, the image and the commit only).
        assertThat(allTexts(session)).contains(transcript);
        assertThat(bridge.sent).hasSize(3);
        verify(session, never()).close(any());
    }

    /**
     * A frame belongs to the utterance it was attached in. One attached in an utterance
     * that never got a turn end must not answer the next utterance's question; a frame
     * attached in the asking utterance does.
     */
    @Test
    void aFrameFromAnUtteranceThatNeverEndedIsNotReused() throws Exception {
        when(locator.locate(anyString(), any())).thenReturn(Optional.of(
                new LiveLocator.Render("六角匙", "六角匙", new double[] {0.2, 0.1, 0.2, 0.2})));
        WebSocketSession session = openedWithLocator();

        // Utterance A: one frame, and no turn end — the bridge answered nothing there.
        handler.handleTextMessage(session, new TextMessage(frame(Base64.getEncoder().encodeToString("a-jpeg".getBytes()))));
        // Utterance B starts and asks to look, with no frame of its own.
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"commit\",\"image\":false}"));
        bridge.listener.onControl(finalTranscript("睇下個六角匙"));
        bridge.listener.onTurnDone();

        verify(locator, after(400).never()).locate(anyString(), any());
        assertThat(phoneFrames(session, "ar_render")).isEmpty();

        // A later utterance attaches its own frame: that one is the question's frame.
        handler.handleTextMessage(session, new TextMessage(frame(Base64.getEncoder().encodeToString("b-jpeg".getBytes()))));
        bridge.listener.onControl(finalTranscript("再睇下個六角匙"));
        bridge.listener.onTurnDone();

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(locator, timeout(1000)).locate(eq("再睇下個六角匙"), bytes.capture());
        assertThat(bytes.getValue()).isEqualTo("b-jpeg".getBytes());
        assertThat(phoneFrames(session, "ar_render")).hasSize(1);
    }

    /** A render already forwarded this turn is the turn's overlay; the backup stays silent. */
    @Test
    void aBridgeRenderThisTurnSuppressesTheBackup() throws Exception {
        WebSocketSession session = openedWithLocator();

        handler.handleTextMessage(session, new TextMessage(frame(Base64.getEncoder().encodeToString("ar-jpeg".getBytes()))));
        bridge.listener.onControl(AR_RENDER);
        bridge.listener.onControl(finalTranscript("睇下個六角匙"));
        bridge.listener.onTurnDone();

        verify(locator, after(400).never()).locate(anyString(), any());
        assertThat(phoneFrames(session, "ar_render")).hasSize(1);
        verify(session, never()).close(any());
    }

    /** No frame, no question: a text-only turn never reaches the direct provider. */
    @Test
    void aTextOnlyTurnNeverCallsTheLocator() throws Exception {
        openedWithLocator();

        bridge.listener.onControl(finalTranscript("邊度有廁所"));
        bridge.listener.onTurnDone();

        verify(locator, after(400).never()).locate(anyString(), any());
    }

    /** A frame without a look intent is not a locator question either. */
    @Test
    void aTranscriptThatIsNotALookIntentNeverCallsTheLocator() throws Exception {
        WebSocketSession session = openedWithLocator();

        handler.handleTextMessage(session, new TextMessage(frame(Base64.getEncoder().encodeToString("ar-jpeg".getBytes()))));
        bridge.listener.onControl(finalTranscript("今日天氣點呀"));
        bridge.listener.onTurnDone();

        verify(locator, after(400).never()).locate(anyString(), any());
        assertThat(phoneFrames(session, "ar_render")).isEmpty();
    }

    /** A final transcript absent, or only a delta, is not a question. */
    @Test
    void anUnfinalisedTranscriptNeverCallsTheLocator() throws Exception {
        WebSocketSession session = openedWithLocator();

        handler.handleTextMessage(session, new TextMessage(frame(Base64.getEncoder().encodeToString("ar-jpeg".getBytes()))));
        bridge.listener.onControl("{\"type\":\"input_transcript\",\"v\":1,\"seq\":1,\"text\":\"睇下\",\"final\":false}");
        bridge.listener.onTurnDone();

        verify(locator, after(400).never()).locate(anyString(), any());
        assertThat(phoneFrames(session, "ar_render")).isEmpty();
    }

    @Test
    void aDisabledLocatorIsNeverAsked() throws Exception {
        WebSocketSession session = openedWithLocator(false);

        handler.handleTextMessage(session, new TextMessage(frame(Base64.getEncoder().encodeToString("ar-jpeg".getBytes()))));
        bridge.listener.onControl(finalTranscript("睇下個六角匙"));
        bridge.listener.onTurnDone();

        verify(locator, after(400).never()).locate(anyString(), any());
        assertThat(phoneFrames(session, "ar_render")).isEmpty();
    }

    /**
     * One backup per turn, and the turn's frame dies with the turn: a second turn end and a
     * later question with no new frame cannot draw again.
     */
    @Test
    void atMostOneBackupRunsPerTurnAndTheFrameDiesWithIt() throws Exception {
        when(locator.locate(anyString(), any())).thenReturn(Optional.of(
                new LiveLocator.Render("六角匙", "六角匙", new double[] {0.2, 0.1, 0.2, 0.2})));
        WebSocketSession session = openedWithLocator();

        handler.handleTextMessage(session, new TextMessage(frame(Base64.getEncoder().encodeToString("ar-jpeg".getBytes()))));
        bridge.listener.onControl(finalTranscript("睇下個六角匙"));
        bridge.listener.onTurnDone();
        awaitPhoneFrame(session, "ar_render");

        bridge.listener.onTurnDone();
        bridge.listener.onControl(finalTranscript("再睇下另一個"));
        bridge.listener.onTurnDone();

        verify(locator, after(400).times(1)).locate(anyString(), any());
        assertThat(phoneFrames(session, "ar_render")).hasSize(1);
    }

    /** A new utterance asks its own question: the previous one is not carried into it. */
    @Test
    void aNewUtteranceDropsThePreviousQuestion() throws Exception {
        WebSocketSession session = openedWithLocator();

        handler.handleTextMessage(session, new TextMessage(frame(Base64.getEncoder().encodeToString("ar-jpeg".getBytes()))));
        bridge.listener.onControl(finalTranscript("睇下個六角匙"));
        // The phone starts a second utterance with no question of its own: the first
        // utterance's question does not carry over to it.
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"commit\",\"image\":false}"));
        bridge.listener.onTurnDone();

        verify(locator, after(400).never()).locate(anyString(), any());
    }

    /**
     * A slow answer cannot draw over the next utterance: the turn it belonged to is over by
     * the time it comes back, so it is dropped instead of painting a stale box.
     */
    @Test
    void anAnswerThatArrivesAfterTheNextUtteranceIsDropped() throws Exception {
        CountDownLatch answer = new CountDownLatch(1);
        when(locator.locate(anyString(), any())).thenAnswer(invocation -> {
            answer.await(2, TimeUnit.SECONDS);
            return Optional.of(new LiveLocator.Render("六角匙", "六角匙", new double[] {0.2, 0.1, 0.2, 0.2}));
        });
        WebSocketSession session = openedWithLocator();

        handler.handleTextMessage(session, new TextMessage(frame(Base64.getEncoder().encodeToString("ar-jpeg".getBytes()))));
        bridge.listener.onControl(finalTranscript("睇下個六角匙"));
        bridge.listener.onTurnDone();
        verify(locator, timeout(1000)).locate(anyString(), any());

        // The phone's next utterance begins before the provider answered.
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"commit\",\"image\":false}"));
        answer.countDown();
        sleep(300);

        assertThat(phoneFrames(session, "ar_render")).isEmpty();
    }

    /** A locator that fails is silent: no render, no error frame, no close. */
    @Test
    void aFailingLocatorIsSilent() throws Exception {
        when(locator.locate(anyString(), any())).thenThrow(new IllegalStateException("provider down"));
        WebSocketSession session = openedWithLocator();

        handler.handleTextMessage(session, new TextMessage(frame(Base64.getEncoder().encodeToString("ar-jpeg".getBytes()))));
        bridge.listener.onControl(finalTranscript("睇下個六角匙"));
        bridge.listener.onTurnDone();

        verify(locator, timeout(1000)).locate(anyString(), any());
        sleep(200);
        assertThat(phoneFrames(session, "ar_render")).isEmpty();
        assertThat(allTexts(session)).noneMatch(text -> text.contains("\"error\""));
        verify(session, never()).close(any());
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
    void aBridgeTurnDoneIsHandedToTheListenerHook() throws Exception {
        WebSocketSession session = opened();

        bridge.listener.onTurnDone();

        assertThat(turnEnds).hasValue(1);
        verify(session, never()).close(any());
    }

    @Test
    void aSocketCloseReleasesTheSinksPark() throws Exception {
        WebSocketSession session = opened();

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThat(sinkCloses).hasValue(1);
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

    /**
     * A failed reopen sends {@code session_expired} itself and then {@code fatal{4012}}: the
     * fatal repeats the error the phone already heard, so no second one is owed.
     */
    @Test
    void aFatalThatRepeatsTheLastErrorSendsNoSecondOne() throws Exception {
        WebSocketSession session = opened();

        bridge.listener.onControl(error("session_expired", true));
        bridge.listener.onFatal(LiveWebSocketHandler.CLOSE_SESSION_EXPIRED);

        List<String> payloads = allTexts(session);
        assertThat(payloads).as("ready and the bridge's own error, no duplicate").hasSize(2);
        assertThat(json.readTree(payloads.get(1)).path("code").asText()).isEqualTo("session_expired");
        verify(session).close(argThat(status -> status.getCode() == 4012));
    }

    /**
     * One error per failure, not one per socket: an earlier forwarded error says nothing
     * about a later hard fatal, which still owes the phone its own reason.
     */
    @Test
    void aFatalThatNamesADifferentFailureStillSendsItsOwnError() throws Exception {
        WebSocketSession session = opened();

        bridge.listener.onControl(error("provider_unavailable", true));
        bridge.listener.onFatal(LiveWebSocketHandler.CLOSE_AUTH);

        List<String> payloads = allTexts(session);
        assertThat(payloads).as("ready, the earlier error, then the fatal's own").hasSize(3);
        JsonNode fatal = json.readTree(payloads.get(2));
        assertThat(fatal.path("type").asText()).isEqualTo("error");
        assertThat(fatal.path("v").asInt()).isEqualTo(1);
        assertThat(fatal.path("code").asText()).isEqualTo("auth_failed");
        assertThat(fatal.path("retryable").asBoolean(true)).isFalse();
        verify(session).close(argThat(status -> status.getCode() == 4001));
    }

    /** A non-error control is not an error and must not latch anything. */
    @Test
    void aControlThatIsNotAnErrorDoesNotLatch() throws Exception {
        WebSocketSession session = opened();

        bridge.listener.onControl("{\"type\":\"speech_end\",\"tMs\":1}");
        bridge.listener.onFatal(LiveWebSocketHandler.CLOSE_BRIDGE);

        List<String> payloads = allTexts(session);
        assertThat(payloads).hasSize(3);
        assertThat(json.readTree(payloads.get(2)).path("code").asText()).isEqualTo("bridge_internal");
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

        handler.handleBinaryMessage(session, new BinaryMessage(ByteBuffer.wrap(new byte[] {1, 2, 3, 4})));
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
        handler = new LiveWebSocketHandler(new LiveProperties(false, null, null, "", 5000, 7200, false, null, null, 0),
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
        handler = new LiveWebSocketHandler(new LiveProperties(false, null, null, "", 5000, 7200, false, null, null, 0),
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
        handler = new LiveWebSocketHandler(new LiveProperties(false, null, null, "", 5000, 7200, false, null, null, 0),
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

    /**
     * A bridge {@code control} event, unwrapped exactly as {@code QwenLiveBridgeProcess}
     * hands it to the listener: the inner {@code event} object, never the stdio envelope.
     */
    private static String error(String code, boolean retryable) {
        return "{\"type\":\"error\",\"v\":1,\"code\":\"" + code
                + "\",\"retryable\":" + retryable + ",\"message\":\"stable sentence\"}";
    }

    private WebSocketSession opened() {
        WebSocketSession session = session("s1", LiveWebSocketHandler.PROTOCOL, LiveWebSocketHandler.PROTOCOL);
        handler(5000).afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage(HELLO));
        return session;
    }

    /** The same socket, with the direct backup locator wired in (spec D6). */
    private WebSocketSession openedWithLocator() {
        return openedWithLocator(true);
    }

    private WebSocketSession openedWithLocator(boolean locatorEnabled) {
        WebSocketSession session = session("s1", LiveWebSocketHandler.PROTOCOL, LiveWebSocketHandler.PROTOCOL);
        handler = new LiveWebSocketHandler(new LiveProperties(false, null, null, "", 5000, 7200,
                locatorEnabled, null, null, 0), bridges, json, conversationId -> List.of(),
                new LiveSessionRegistry(), sink, new LiveFrames(json), locator);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage(HELLO));
        return session;
    }

    /** A final {@code input_transcript} control, exactly as the bridge emits it. */
    private static String finalTranscript(String text) {
        return "{\"type\":\"input_transcript\",\"v\":1,\"seq\":2,\"text\":\"" + text + "\",\"final\":true}";
    }

    /** The bridge's own render, as forwarded to the phone (spec D1). */
    private static final String AR_RENDER = "{\"type\":\"ar_render\",\"v\":1,"
            + "\"renderId\":\"5d0c9e0f-1111-4a4a-8a8a-001122334455\",\"render\":{\"primitive\":\"highlight\","
            + "\"target\":{\"label\":\"六角匙\",\"bbox\":[0.4,0.5,0.1,0.1]},\"text\":\"六角匙\","
            + "\"ttlMs\":8000,\"coord\":\"normalized\",\"safetyAlert\":null}}";

    /** Every phone text frame of one type, in the order the socket saw them. */
    private List<JsonNode> phoneFrames(WebSocketSession session, String type) throws Exception {
        List<JsonNode> matched = new ArrayList<>();
        for (String payload : allTexts(session)) {
            JsonNode node = json.readTree(payload);
            if (type.equals(node.path("type").asText())) {
                matched.add(node);
            }
        }
        return matched;
    }

    /** Waits for one phone frame of {@code type}: the backup's render is asynchronous. */
    private JsonNode awaitPhoneFrame(WebSocketSession session, String type) throws Exception {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            List<JsonNode> found = phoneFrames(session, type);
            if (!found.isEmpty()) {
                return found.get(0);
            }
            Thread.sleep(10);
        }
        throw new AssertionError("no " + type + " frame reached the phone within 2 s");
    }

    private static void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    private LiveWebSocketHandler handler(long readyDeadlineMs) {
        handler = new LiveWebSocketHandler(new LiveProperties(false, null, null, "", readyDeadlineMs, 7200, false, null, null, 0),
                bridges, json, conversationId -> List.of(), new LiveSessionRegistry(), sink);
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

    private String lastText(WebSocketSession session) throws Exception {
        List<String> texts = allTexts(session);
        return texts.get(texts.size() - 1);
    }

    private static String frame(String base64) {
        return "{\"type\":\"frame\",\"mime\":\"image/jpeg\",\"dataBase64\":\"" + base64
                + "\",\"capturedAtMs\":1}";
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
