package io.aria.conductor.aria.live;

import org.springframework.web.socket.WebSocketSession;

import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One phone socket's state (spec §"Architecture", "LiveSession").
 *
 * <p>It owns the bridge lifecycle: the bridge is opened after {@code ready} and reaped
 * when the socket closes. {@code runId} stays {@code null} until the first tool call of a
 * turn creates the lazy live run (Task 3); a pure chat turn never gets one. The Qwen
 * session id never lives here — {@link #id()} is Aria's own per-socket UUID, the only id
 * that appears in {@code ready} or in a log line.
 *
 * <p>The pending photo frame is the one frame allowed per utterance (spec X10): it is
 * held in memory and handed to the bridge only with {@code commit{image:true}}.
 */
public final class LiveSession {

    private final String id;
    private final WebSocketSession ws;
    private final String offerSubprotocol;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean hello = new AtomicBoolean();

    private volatile String conversationId;
    private volatile UUID runId;
    private volatile LiveBridge bridge;
    private volatile boolean micOn = true;
    private volatile String pendingFrameBase64;
    private volatile ScheduledFuture<?> helloDeadline;

    public LiveSession(String id, String conversationId, WebSocketSession ws, String offerSubprotocol) {
        this.id = id;
        this.conversationId = conversationId;
        this.ws = ws;
        this.offerSubprotocol = offerSubprotocol;
    }

    /** Aria's per-socket UUID; never a Qwen id. */
    public String id() {
        return id;
    }

    public String conversationId() {
        return conversationId;
    }

    /** Binds the socket to its conversation: one socket, one conversation (spec X1). */
    void bindConversation(String conversationId) {
        this.conversationId = conversationId;
    }

    public WebSocketSession ws() {
        return ws;
    }

    /** The raw {@code Sec-WebSocket-Protocol} the client offered, or null when it offered none. */
    public String offerSubprotocol() {
        return offerSubprotocol;
    }

    /** The lazy live run for the current tool turn, or null when this turn created none. */
    public UUID runId() {
        return runId;
    }

    public void runId(UUID runId) {
        this.runId = runId;
    }

    public LiveBridge bridge() {
        return bridge;
    }

    public void bridge(LiveBridge bridge) {
        this.bridge = bridge;
    }

    /** @return true for the first {@code hello} of this socket only */
    public boolean claimHello() {
        return hello.compareAndSet(false, true);
    }

    public boolean helloSeen() {
        return hello.get();
    }

    /** @return true the first time only: the socket is closed exactly once */
    public boolean markClosed() {
        return closed.compareAndSet(false, true);
    }

    public boolean closed() {
        return closed.get();
    }

    /** A phone {@code mic{on:false}} stops the inbound audio path until it is turned back on. */
    public boolean micOn() {
        return micOn;
    }

    public void micOn(boolean micOn) {
        this.micOn = micOn;
    }

    public void pendingFrameBase64(String pendingFrameBase64) {
        this.pendingFrameBase64 = pendingFrameBase64;
    }

    /** @return the pending frame and forgets it: one frame per utterance */
    public String takePendingFrameBase64() {
        String pending = pendingFrameBase64;
        pendingFrameBase64 = null;
        return pending;
    }

    void helloDeadline(ScheduledFuture<?> helloDeadline) {
        this.helloDeadline = helloDeadline;
    }

    void cancelHelloDeadline() {
        ScheduledFuture<?> pending = helloDeadline;
        if (pending != null) {
            pending.cancel(false);
        }
    }

    /** One lock per socket, so phone writes and bridge writes never interleave a frame. */
    Object lock() {
        return ws;
    }
}
