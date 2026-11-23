package io.aria.conductor.aria.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The phone-facing end of the live socket (spec X1 / "Endpoint and handshake").
 *
 * <p>Task 1 owns the opening act only: {@code hello} validates, {@code ready} is
 * sent immediately (it must not wait for any provider connection), the subprotocol
 * Spring accepted is enforced, and a socket that stays silent past the deadline
 * gets a {@code capability_mismatch} error and closes 4002. Everything a
 * {@code hello} unlocks (bridge, tools, frames, approvals) arrives in Tasks 2-4;
 * that is why the bridge seam is a no-op factory here.
 *
 * <p>Logs are metadata only (spec L6): event types, refusals and close codes,
 * never payloads, bearer values or ids beyond a debug line.
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
    static final String ERROR_CAPABILITY_MISMATCH = "capability_mismatch";
    static final String ERROR_MESSAGE_HELLO_REJECTED = "hello rejected";
    static final String ERROR_MESSAGE_HELLO_DEADLINE = "hello deadline exceeded";

    private static final int MAX_CONVERSATION_ID_LENGTH = 36;
    private static final int HELLO_VERSION = 1;
    private static final int IN_RATE = 16000;
    private static final int OUT_RATE = 24000;
    private static final int CHANNELS = 1;
    private static final String AUDIO_FORMAT = "pcm16le";

    private static final Logger log = LoggerFactory.getLogger(LiveWebSocketHandler.class);

    private final LiveProperties properties;
    private final LiveBridgeFactory bridges;
    private final ObjectMapper json;
    private final Map<String, LiveSocket> sockets = new ConcurrentHashMap<>();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "aria-live-hello-deadline");
        thread.setDaemon(true);
        return thread;
    });

    public LiveWebSocketHandler(LiveProperties properties, LiveBridgeFactory bridges, ObjectMapper json) {
        this.properties = properties;
        this.bridges = bridges;
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        LiveSocket socket = new LiveSocket(session,
                UUID.randomUUID().toString(),
                attribute(session, LiveHandshakeInterceptor.OFFERED_PROTOCOL_ATTRIBUTE));
        sockets.put(session.getId(), socket);
        socket.deadline = deadlines.schedule(socket::expireHello,
                properties.readyDeadlineMs(), TimeUnit.MILLISECONDS);
        log.debug("aria live socket opened");
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        LiveSocket socket = sockets.get(session.getId());
        if (socket == null) {
            return;
        }
        JsonNode frame;
        try {
            frame = json.readTree(message.getPayload());
        } catch (IOException e) {
            reject(socket);
            return;
        }
        if (frame == null || !"hello".equals(frame.path("type").asText(""))) {
            // Nothing else is legal before hello, and a second hello is a second
            // binding for one conversation (spec X1).
            reject(socket);
            return;
        }
        if (!socket.claimHello() || !validHello(frame)) {
            reject(socket);
            return;
        }
        socket.cancelDeadline();
        send(socket, ready(socket.sessionId));
        socket.bridge = bridges.open(socket.sessionId, frame.path("conversationId").asText());
        log.debug("aria live hello accepted");
        if (socket.offeredProtocol != null && !PROTOCOL.equals(socket.session.getAcceptedProtocol())) {
            // Refused by what Spring actually accepted, not by the raw header: a
            // client offering "aria-live.v1, x" was served aria-live.v1 and stays
            // open (spec L3), while an offer the server could not honour gets its
            // ready frame and then a clean 4002.
            close(socket, ERROR_CAPABILITY_MISMATCH);
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        // Task 2 owns the inbound 16 kHz PCM path and its 32 KiB cap; until then a
        // binary frame is dropped rather than tearing the socket down.
        log.debug("aria live binary frame dropped: phase=pre-bridge");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        LiveSocket socket = sockets.remove(session.getId());
        if (socket == null) {
            return;
        }
        socket.cancelDeadline();
        socket.closed.set(true);
        LiveBridge bridge = socket.bridge;
        if (bridge != null) {
            try {
                bridge.close();
            } catch (RuntimeException e) {
                log.debug("aria live bridge close failed");
            }
        }
        log.debug("aria live socket closed: code={}", status.getCode());
    }

    @PreDestroy
    void shutdown() {
        deadlines.shutdownNow();
    }

    // --- frames ---------------------------------------------------------------------------

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

    private void send(LiveSocket socket, Map<String, Object> payload) {
        if (socket.closed.get()) {
            return;
        }
        try {
            socket.session.sendMessage(new TextMessage(json.writeValueAsString(payload)));
        } catch (IOException e) {
            log.debug("aria live send failed");
        }
    }

    /** {@code capability_mismatch} then 4002: the one refusal this task emits. */
    private void reject(LiveSocket socket) {
        refuse(socket, ERROR_MESSAGE_HELLO_REJECTED);
    }

    private void refuse(LiveSocket socket, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", "error");
        error.put("v", HELLO_VERSION);
        error.put("code", ERROR_CAPABILITY_MISMATCH);
        error.put("retryable", false);
        error.put("message", message);
        send(socket, error);
        close(socket, ERROR_CAPABILITY_MISMATCH);
    }

    private void close(LiveSocket socket, String reason) {
        socket.cancelDeadline();
        if (!socket.closed.compareAndSet(false, true)) {
            return;
        }
        try {
            socket.session.close(new CloseStatus(CLOSE_PROTOCOL, reason));
        } catch (IOException e) {
            log.debug("aria live close failed");
        }
    }

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

    private static String attribute(WebSocketSession session, String name) {
        Object value = session.getAttributes().get(name);
        return value instanceof String text ? text : null;
    }

    /** One socket's state until Task 2's {@code LiveSession} takes it over. */
    private final class LiveSocket {

        final WebSocketSession session;
        final String sessionId;
        /** The raw offer, or null; the refusal itself reads the accepted protocol. */
        final String offeredProtocol;
        final AtomicBoolean closed = new AtomicBoolean();
        volatile ScheduledFuture<?> deadline;
        volatile LiveBridge bridge;
        private boolean hello;

        LiveSocket(WebSocketSession session, String sessionId, String offeredProtocol) {
            this.session = session;
            this.sessionId = sessionId;
            this.offeredProtocol = offeredProtocol;
        }

        /** @return true for the first hello of this socket only */
        synchronized boolean claimHello() {
            if (hello) {
                return false;
            }
            hello = true;
            return true;
        }

        void cancelDeadline() {
            ScheduledFuture<?> pending = deadline;
            if (pending != null) {
                pending.cancel(false);
            }
        }

        void expireHello() {
            if (!closed.get()) {
                log.debug("aria live hello deadline missed");
                // Same shape as the invalid-hello refusal: say why, then close.
                refuse(this, ERROR_MESSAGE_HELLO_DEADLINE);
            }
        }
    }
}
