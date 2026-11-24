package io.aria.conductor.aria.live;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The phone-facing end of the live socket (spec X1 / §"Endpoint and handshake", §"Bridge
 * process").
 *
 * <p>The opening act validates {@code hello}, sends {@code ready} immediately (it must not
 * wait for any provider connection), enforces the accepted subprotocol and the hello
 * deadline. Everything after it is one bridge per socket: the conversation's last 20
 * user/assistant turns seed the child's {@code hello}, phone frames are routed down, and
 * the child's control/audio/tool_call lines are routed up. When the bridge cannot be
 * started — disabled live path, unusable credentials, a factory failure, or an unexpected
 * child exit — the socket says {@code bridge_internal} and closes 1011.
 *
 * <p>Limits (spec "Limits"): a text frame over 64 KiB is dropped, a binary frame over
 * 32 KiB is dropped, and an image frame is validated by {@link LiveFrameValidator} and
 * refused with {@code frame_too_large}/{@code frame_invalid} while the socket stays open.
 * A {@code frame} message may legally be larger than 64 KiB (its own limit is 256 KiB of
 * base64), so it alone is read past that cap; one whose envelope exceeds
 * {@link #MAX_FRAME_MESSAGE_BYTES} is answered {@code frame_too_large} and the socket kept,
 * because a silent drop would leave the phone waiting for a reply forever.
 *
 * <p>The container's own text buffer ({@code LiveWebSocketConfig}, raised to 512 KiB for
 * this endpoint and its STOMP co-tenant {@code /ws/events}) is the hard ceiling: a frame
 * message larger than that never reaches this class and the container closes 1009 on it.
 * Everything between {@link #MAX_FRAME_MESSAGE_BYTES} and that buffer is refused here.
 *
 * <p>Logs are metadata only (spec L6): event types, byte counts and close codes, never a
 * payload, a bearer, a key, a workspace or an id beyond a debug phase.
 */
@Component
public class LiveWebSocketHandler extends TextWebSocketHandler {

    /** The one protocol value the phone speaks (spec L3). */
    public static final String PROTOCOL = "aria-live.v1";
    /** The wire header the client offers {@link #PROTOCOL} on. */
    static final String PROTOCOL_HEADER = "Sec-WebSocket-Protocol";
    /** Advertised in {@code ready}; the bridge, not this class, talks to Qwen. */
    static final String MODEL = "qwen3.8-omni-flash-realtime";

    /** Protocol/version refusal (spec close codes). */
    static final int CLOSE_PROTOCOL = 4002;
    /** Bridge authentication refusal (spec close codes). */
    static final int CLOSE_AUTH = 4001;
    /** A failed 120-minute reopen (spec close codes). */
    static final int CLOSE_SESSION_EXPIRED = 4012;
    /** Bridge failure or unexpected child exit (spec close codes). */
    static final int CLOSE_BRIDGE = 1011;

    static final String ERROR_AUTH_FAILED = "auth_failed";
    static final String ERROR_CAPABILITY_MISMATCH = "capability_mismatch";
    static final String ERROR_SESSION_EXPIRED = "session_expired";
    static final String ERROR_BRIDGE_INTERNAL = "bridge_internal";
    static final String ERROR_FRAME_TOO_LARGE = "frame_too_large";
    static final String ERROR_FRAME_INVALID = "frame_invalid";

    static final String ERROR_MESSAGE_HELLO_REJECTED = "hello rejected";
    static final String ERROR_MESSAGE_HELLO_DEADLINE = "hello deadline exceeded";
    static final String ERROR_MESSAGE_BRIDGE_UNAVAILABLE = "live bridge unavailable";
    static final String ERROR_MESSAGE_AUTH_FAILED = "Qwen rejected the bridge credentials";
    static final String ERROR_MESSAGE_SESSION_EXPIRED = "Qwen session expired";
    static final String ERROR_MESSAGE_CAPABILITY = "live bridge capability mismatch";
    static final String ERROR_MESSAGE_FRAME_TOO_LARGE = "frame too large";
    static final String ERROR_MESSAGE_FRAME_ENVELOPE = "frame exceeds 256 KiB base64";
    static final String ERROR_MESSAGE_FRAME_INVALID = "frame invalid";

    /** A phone control frame may not exceed 64 KiB. */
    static final int MAX_TEXT_FRAME_BYTES = 64 * 1024;
    /** A {@code frame} message: 256 KiB of base64 plus its JSON envelope. */
    static final int MAX_FRAME_MESSAGE_BYTES = 320 * 1024;
    /** A phone audio frame may not exceed 32 KiB of PCM. */
    static final int MAX_BINARY_FRAME_BYTES = 32 * 1024;

    private static final int MAX_CONVERSATION_ID_LENGTH = 36;
    private static final int HELLO_VERSION = LiveFrames.VERSION;
    private static final int IN_RATE = 16000;
    private static final int OUT_RATE = 24000;
    private static final int CHANNELS = 1;
    private static final String AUDIO_FORMAT = "pcm16le";

    private static final Logger log = LoggerFactory.getLogger(LiveWebSocketHandler.class);

    private final LiveProperties properties;
    private final LiveBridgeFactory bridges;
    private final ObjectMapper json;
    private final LiveContextSource context;
    private final LiveSessionRegistry registry;
    private final LiveToolCallSink toolCalls;
    private final LiveFrames frames;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "aria-live-hello-deadline");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    public LiveWebSocketHandler(LiveProperties properties, LiveBridgeFactory bridges, ObjectMapper json,
            LiveContextSource context, LiveSessionRegistry registry,
            ObjectProvider<LiveToolCallSink> toolCalls, LiveFrames frames) {
        this(properties, bridges, json, context, registry,
                toolCalls.getIfAvailable(() -> LiveToolCallSink.NONE), frames);
    }

    LiveWebSocketHandler(LiveProperties properties, LiveBridgeFactory bridges, ObjectMapper json) {
        this(properties, bridges, json, conversationId -> List.of(), new LiveSessionRegistry(),
                LiveToolCallSink.NONE, new LiveFrames(json));
    }

    LiveWebSocketHandler(LiveProperties properties, LiveBridgeFactory bridges, ObjectMapper json,
            LiveContextSource context) {
        this(properties, bridges, json, context, new LiveSessionRegistry(), LiveToolCallSink.NONE,
                new LiveFrames(json));
    }

    LiveWebSocketHandler(LiveProperties properties, LiveBridgeFactory bridges, ObjectMapper json,
            LiveContextSource context, LiveSessionRegistry registry, LiveToolCallSink toolCalls) {
        this(properties, bridges, json, context, registry, toolCalls, new LiveFrames(json));
    }

    LiveWebSocketHandler(LiveProperties properties, LiveBridgeFactory bridges, ObjectMapper json,
            LiveContextSource context, LiveSessionRegistry registry, LiveToolCallSink toolCalls,
            LiveFrames frames) {
        this.properties = properties;
        this.bridges = bridges;
        this.json = json;
        this.context = context;
        this.registry = registry;
        this.toolCalls = toolCalls;
        this.frames = frames;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        LiveSession live = new LiveSession(UUID.randomUUID().toString(), null, session,
                attribute(session, LiveHandshakeInterceptor.OFFERED_PROTOCOL_ATTRIBUTE));
        registry.register(live);
        live.helloDeadline(deadlines.schedule(() -> expireHello(live),
                properties.readyDeadlineMs(), TimeUnit.MILLISECONDS));
        log.debug("aria live socket opened");
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        LiveSession live = registry.get(session.getId());
        if (live == null) {
            return;
        }
        String payload = message.getPayload();
        int bytes = payload.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_TEXT_FRAME_BYTES) {
            if (!"frame".equals(probeType(payload))) {
                // Metadata only: a dropped frame is a byte count, never its content.
                log.debug("aria live text frame dropped: bytes={}", bytes);
                return;
            }
            if (bytes > MAX_FRAME_MESSAGE_BYTES) {
                // Past the envelope 256 KiB of base64 can occupy, so it is refused before
                // any parse and the socket stays open for the next (legal) frame.
                log.debug("aria live frame envelope refused: bytes={}", bytes);
                sendError(live, ERROR_FRAME_TOO_LARGE, false, ERROR_MESSAGE_FRAME_ENVELOPE);
                return;
            }
        }
        JsonNode frame;
        try {
            frame = json.readTree(payload);
        } catch (IOException e) {
            refuseOrDrop(live);
            return;
        }
        if (frame == null || !frame.isObject()) {
            refuseOrDrop(live);
            return;
        }
        String type = frame.path("type").asText("");
        if (bytes > MAX_TEXT_FRAME_BYTES && !"frame".equals(type)) {
            // The probe reads the first "type" key while Jackson keeps the last, so the
            // 64 KiB cap is enforced again on the message that was actually parsed.
            log.debug("aria live text frame dropped: bytes={}", bytes);
            return;
        }
        if (!live.helloSeen()) {
            if ("mic".equals(type)) {
                // The phone mirrors its mic state before hello (observed live 2026-11-24).
                // hello carries the authoritative mic flag, so the early frame is ignored
                // rather than refused; every other non-hello frame still closes 4002.
                return;
            }
            if (!"hello".equals(type) || !live.claimHello() || !validHello(frame)) {
                reject(live);
                return;
            }
            acceptHello(live, frame);
            return;
        }
        route(live, type, frame);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        LiveSession live = registry.get(session.getId());
        if (live == null) {
            return;
        }
        int bytes = message.getPayloadLength();
        if (bytes > MAX_BINARY_FRAME_BYTES) {
            log.debug("aria live binary frame dropped: bytes={}", bytes);
            return;
        }
        LiveBridge bridge = live.bridge();
        if (bridge == null) {
            return;
        }
        if (!live.micOn()) {
            log.debug("aria live binary frame dropped: mic_off bytes={}", bytes);
            return;
        }
        ByteBuffer payload = message.getPayload();
        byte[] pcm = new byte[bytes];
        payload.duplicate().get(pcm);
        bridge.sendBinary(pcm);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        LiveSession live = registry.remove(session.getId());
        if (live == null) {
            return;
        }
        live.cancelHelloDeadline();
        live.markClosed();
        reap(live);
        releasePark(live);
        log.debug("aria live socket closed: code={}", status.getCode());
    }

    @PreDestroy
    void shutdown() {
        deadlines.shutdownNow();
    }

    /** The registry this handler owns, so a later router can find a run's socket. */
    LiveSessionRegistry registry() {
        return registry;
    }

    // --- hello and ready ------------------------------------------------------------------

    private void acceptHello(LiveSession live, JsonNode hello) {
        live.cancelHelloDeadline();
        live.bindConversation(hello.path("conversationId").asText());
        live.micOn(hello.path("mic").asBoolean(true));
        send(live, ready(live.id()));
        log.debug("aria live hello accepted");
        if (live.offerSubprotocol() != null && !PROTOCOL.equals(live.ws().getAcceptedProtocol())) {
            // Refused by what Spring actually accepted, not by the raw header: a
            // client offering "aria-live.v1, x" was served aria-live.v1 and stays
            // open (spec L3), while an offer the server could not honour gets its
            // ready frame and then a clean 4002. No child is spawned for this.
            close(live, CLOSE_PROTOCOL, ERROR_CAPABILITY_MISMATCH);
            return;
        }
        try {
            live.bridge(bridges.open(live, new BridgeListener(live)));
            if (live.closed()) {
                // The hello deadline fired while the child was starting: the socket is
                // gone, so the child must go with it rather than outlive its only reader.
                reap(live);
                return;
            }
            live.bridge().send(bridgeHello(live));
        } catch (RuntimeException e) {
            // The reason is a class name only: a failure message could carry a path or a
            // credential fragment, and none of it belongs in a log line.
            log.debug("aria live bridge unavailable: reason={}", e.getClass().getSimpleName());
            bridgeUnavailable(live);
        }
    }

    /** {@code {"type":"hello","v":1,"conversationId":…,"context":[…]}} — spec Task 2 Step 4. */
    private String bridgeHello(LiveSession live) {
        List<LiveContextSource.Entry> entries;
        try {
            List<LiveContextSource.Entry> seeded = context.context(live.conversationId());
            entries = seeded == null ? List.of() : seeded;
        } catch (RuntimeException e) {
            log.debug("aria live context unavailable: reason={}", e.getClass().getSimpleName());
            entries = List.of();
        }
        Map<String, Object> hello = new LinkedHashMap<>();
        hello.put("type", "hello");
        hello.put("v", HELLO_VERSION);
        hello.put("conversationId", live.conversationId());
        hello.put("context", entries);
        try {
            return json.writeValueAsString(hello);
        } catch (JsonProcessingException e) {
            // The context is plain strings; this cannot happen. Say hello without it
            // rather than fail the socket over a seed.
            log.debug("aria live hello not serializable");
            return "{\"type\":\"hello\",\"v\":" + HELLO_VERSION + ",\"conversationId\":"
                    + quote(live.conversationId()) + ",\"context\":[]}";
        }
    }

    private Map<String, Object> ready(String sessionId) {
        Map<String, Object> ready = new LinkedHashMap<>();
        ready.put("type", "ready");
        ready.put("v", HELLO_VERSION);
        ready.put("sessionId", sessionId);
        ready.put("model", MODEL);
        ready.put("modalities", List.of("text", "audio"));
        ready.put("maxSessionSec", properties.maxSessionSeconds());
        return ready;
    }

    // --- phone frames after hello ---------------------------------------------------------

    private void route(LiveSession live, String type, JsonNode frame) {
        switch (type) {
            case "hello" -> reject(live);
            case "text" -> {
                Map<String, Object> text = new LinkedHashMap<>();
                text.put("type", "text");
                text.put("text", frame.path("text").asText(""));
                forward(live, text);
            }
            case "mic" -> {
                boolean on = frame.path("on").asBoolean(true);
                live.micOn(on);
                Map<String, Object> mic = new LinkedHashMap<>();
                mic.put("type", "mic");
                mic.put("on", on);
                forward(live, mic);
            }
            case "cancel" -> forward(live, Map.of("type", "cancel"));
            case "frame" -> acceptFrame(live, frame);
            case "commit" -> commit(live, frame);
            default -> log.debug("aria live phone frame dropped: type={}", type.isBlank() ? "absent" : type);
        }
    }

    private void acceptFrame(LiveSession live, JsonNode frame) {
        LiveFrameValidator.Result result = LiveFrameValidator.validate(
                frame.path("mime").asText(null), frame.path("dataBase64").asText(null));
        if (result.failure() == LiveFrameValidator.Failure.TOO_LARGE) {
            // Not retryable: the phone must shrink the frame, never resend the same bytes
            // (contract error table). The envelope refusal above answers identically.
            sendError(live, ERROR_FRAME_TOO_LARGE, false, ERROR_MESSAGE_FRAME_TOO_LARGE);
            return;
        }
        if (!result.accepted()) {
            sendError(live, ERROR_FRAME_INVALID, true, ERROR_MESSAGE_FRAME_INVALID);
            return;
        }
        // One frame per utterance: a newer frame replaces the pending one.
        live.pendingFrameBase64(frame.path("dataBase64").asText());
    }

    private void commit(LiveSession live, JsonNode frame) {
        boolean wantsImage = frame.path("image").asBoolean(false);
        String pending = live.takePendingFrameBase64();
        boolean image = wantsImage && pending != null;
        if (image) {
            Map<String, Object> imageFrame = new LinkedHashMap<>();
            imageFrame.put("type", "image");
            imageFrame.put("dataBase64", pending);
            forward(live, imageFrame);
        }
        Map<String, Object> commit = new LinkedHashMap<>();
        commit.put("type", "commit");
        commit.put("image", image);
        forward(live, commit);
    }

    private void forward(LiveSession live, Map<String, Object> message) {
        LiveBridge bridge = live.bridge();
        if (bridge == null) {
            return;
        }
        try {
            bridge.send(json.writeValueAsString(message));
        } catch (JsonProcessingException e) {
            log.debug("aria live bridge frame not serializable");
        }
    }

    // --- bridge → phone -------------------------------------------------------------------

    private final class BridgeListener implements LiveBridgeListener {

        private final LiveSession live;
        /**
         * The code of the last {@code error} event that crossed this socket; null before
         * the first. A failed reopen sends {@code session_expired} and then
         * {@code fatal{4012}}: the fatal repeats an error the phone already heard, so only
         * that repetition is suppressed. A fatal that names a *different* failure still
         * owes the phone its own reason — a stale error is not the current one.
         */
        private final AtomicReference<String> lastErrorCode = new AtomicReference<>();

        BridgeListener(LiveSession live) {
            this.live = live;
        }

        @Override
        public void onControl(String payload) {
            String code = errorCode(payload);
            if (code != null) {
                lastErrorCode.set(code);
            }
            log.debug("aria live -> phone control: type={}", eventType(payload));
            sendRaw(live, payload);
        }

        @Override
        public void onAudio(byte[] pcm) {
            sendBinary(live, pcm);
        }

        @Override
        public void onToolCall(String json) {
            try {
                toolCalls.onToolCall(live, json);
            } catch (RuntimeException e) {
                log.debug("aria live tool call sink failed: reason={}", e.getClass().getSimpleName());
            }
        }

        @Override
        public void onTurnDone() {
            try {
                toolCalls.onTurnDone(live);
            } catch (RuntimeException e) {
                log.debug("aria live turn-end sink failed: reason={}", e.getClass().getSimpleName());
            }
        }

        @Override
        public void onFatal(int code) {
            log.debug("aria live bridge fatal: code={}", code);
            Fatal fatal = Fatal.of(code);
            // Exactly one error per failure: the fatal's own code is the only error it may
            // consider already delivered, and the compare-and-set keeps two concurrent
            // fatals from both sending.
            String previous = lastErrorCode.getAndSet(fatal.errorCode());
            fatal(live, fatal, !fatal.errorCode().equals(previous));
        }

        @Override
        public void onExit(int code) {
            log.debug("aria live bridge exited: code={}", code);
            if (!live.closed()) {
                // The child died under an open socket: say why, then 1011 (spec close codes).
                bridgeUnavailable(live);
            }
        }

        /** @return the code of a phone {@code error} event, or null when this is not one */
        private String errorCode(String payload) {
            try {
                JsonNode event = json.readTree(payload == null ? "" : payload);
                if (event == null || !"error".equals(event.path("type").asText(""))) {
                    return null;
                }
                return event.path("code").asText("");
            } catch (IOException e) {
                return null;
            }
        }
    }

    // --- sending and closing --------------------------------------------------------------

    private void send(LiveSession live, Map<String, Object> payload) {
        frames.toPhone(live, payload);
    }


    /** The event type only: a log line never carries a payload. */
    private String eventType(String payload) {
        try {
            return json.readTree(payload).path("type").asText("?");
        } catch (Exception e) {
            return "unparseable";
        }
    }

    private void sendRaw(LiveSession live, String payload) {
        frames.toPhoneRaw(live, payload);
    }

    private void sendBinary(LiveSession live, byte[] pcm) {
        if (live.closed() || pcm == null || pcm.length == 0) {
            return;
        }
        synchronized (live.lock()) {
            try {
                live.ws().sendMessage(new BinaryMessage(pcm));
            } catch (IOException | IllegalStateException e) {
                log.debug("aria live binary send failed");
            }
        }
    }

    private void sendError(LiveSession live, String code, boolean retryable, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", "error");
        error.put("v", HELLO_VERSION);
        error.put("code", code);
        error.put("retryable", retryable);
        error.put("message", message);
        send(live, error);
    }

    /** {@code bridge_internal}, retryable, then 1011 — every way the bridge can be missing. */
    private void bridgeUnavailable(LiveSession live) {
        sendError(live, ERROR_BRIDGE_INTERNAL, true, ERROR_MESSAGE_BRIDGE_UNAVAILABLE);
        close(live, CLOSE_BRIDGE, ERROR_BRIDGE_INTERNAL);
    }

    /**
     * A bridge {@code fatal} (spec X9): the code names both the close the phone sees and the
     * error frame that explains it. The error is sent unless the same error already crossed
     * this socket — a failed reopen emits {@code session_expired} itself, then
     * {@code fatal{4012}}, and the contract forbids a second error for one failure.
     */
    private void fatal(LiveSession live, Fatal fatal, boolean errorOwed) {
        if (errorOwed) {
            sendError(live, fatal.errorCode(), fatal.retryable(), fatal.message());
        }
        close(live, fatal.closeCode(), fatal.errorCode());
    }

    /** The fatal close codes the bridge contract names, mapped to the phone's error set. */
    record Fatal(int closeCode, String errorCode, boolean retryable, String message) {

        static Fatal of(int code) {
            return switch (code) {
                case CLOSE_AUTH -> new Fatal(CLOSE_AUTH, ERROR_AUTH_FAILED, false, ERROR_MESSAGE_AUTH_FAILED);
                case CLOSE_PROTOCOL ->
                        new Fatal(CLOSE_PROTOCOL, ERROR_CAPABILITY_MISMATCH, false, ERROR_MESSAGE_CAPABILITY);
                case CLOSE_SESSION_EXPIRED ->
                        new Fatal(CLOSE_SESSION_EXPIRED, ERROR_SESSION_EXPIRED, true, ERROR_MESSAGE_SESSION_EXPIRED);
                // 1011 and anything a bridge should not have sent: the retryable internal failure.
                default -> new Fatal(CLOSE_BRIDGE, ERROR_BRIDGE_INTERNAL, true, ERROR_MESSAGE_BRIDGE_UNAVAILABLE);
            };
        }
    }

    /** {@code capability_mismatch} then 4002: the hello-side refusal. */
    private void reject(LiveSession live) {
        refuse(live, ERROR_MESSAGE_HELLO_REJECTED);
    }

    private void refuseOrDrop(LiveSession live) {
        if (live.helloSeen()) {
            log.debug("aria live phone frame dropped: unparseable");
            return;
        }
        reject(live);
    }

    private void refuse(LiveSession live, String message) {
        sendError(live, ERROR_CAPABILITY_MISMATCH, false, message);
        close(live, CLOSE_PROTOCOL, ERROR_CAPABILITY_MISMATCH);
    }

    private void close(LiveSession live, int code, String reason) {
        live.cancelHelloDeadline();
        if (!live.markClosed()) {
            return;
        }
        reap(live);
        releasePark(live);
        try {
            live.ws().close(new CloseStatus(code, reason));
        } catch (IOException | IllegalStateException e) {
            log.debug("aria live close failed");
        }
    }

    /**
     * No park may outlive the socket that answers it: a WAITING_INPUT run whose socket is
     * gone has no finalize and no approval route left. Idempotent — both the close path
     * here and the container's own close callback release it, and the second release is a
     * no-op.
     */
    private void releasePark(LiveSession live) {
        try {
            toolCalls.onClosed(live);
        } catch (RuntimeException e) {
            log.debug("aria live close sink failed: reason={}", e.getClass().getSimpleName());
        }
    }

    /** No socket close may leave a child behind (spec §Deployment). */
    private void reap(LiveSession live) {
        LiveBridge bridge = live.bridge();
        if (bridge == null) {
            return;
        }
        try {
            bridge.close();
        } catch (RuntimeException e) {
            log.debug("aria live bridge close failed: reason={}", e.getClass().getSimpleName());
        }
    }

    private void expireHello(LiveSession live) {
        if (!live.closed()) {
            log.debug("aria live hello deadline missed");
            // Same shape as the invalid-hello refusal: say why, then close.
            refuse(live, ERROR_MESSAGE_HELLO_DEADLINE);
        }
    }

    // --- validation -----------------------------------------------------------------------

    private static boolean validHello(JsonNode hello) {
        if (hello.path("v").asInt(-1) != HELLO_VERSION) {
            return false;
        }
        String conversationId = hello.path("conversationId").asText("");
        if (conversationId.isBlank() || conversationId.length() > MAX_CONVERSATION_ID_LENGTH) {
            return false;
        }
        JsonNode audio = hello.path("audio");
        return audio.path("inRate").asInt(-1) == IN_RATE
                && audio.path("outRate").asInt(-1) == OUT_RATE
                && AUDIO_FORMAT.equals(audio.path("format").asText(""))
                && audio.path("channels").asInt(-1) == CHANNELS;
    }

    /**
     * The cheap type probe an oversized text frame gets: it reads only up to {@code type},
     * so a 300 KiB blob is classified without materialising its base64 value. A frame the
     * probe cannot read is not a {@code frame}. It reads the first {@code type} key while
     * Jackson keeps the last, so the caller re-checks the parsed type before trusting it.
     */
    private String probeType(String payload) {
        try (JsonParser parser = json.getFactory().createParser(payload)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return "";
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String name = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("type".equals(name)) {
                    return parser.getValueAsString("");
                }
                if (value != null && value.isStructStart()) {
                    parser.skipChildren();
                }
            }
        } catch (IOException e) {
            return "";
        }
        return "";
    }

    private static String attribute(WebSocketSession session, String name) {
        Object value = session.getAttributes().get(name);
        return value instanceof String text ? text : null;
    }

    private static String quote(String value) {
        if (value == null) {
            return "\"\"";
        }
        StringBuilder quoted = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') {
                quoted.append('\\');
            }
            quoted.append(c);
        }
        return quoted.append('"').toString();
    }
}
