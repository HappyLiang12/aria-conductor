package io.aria.conductor.aria.live;

import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * <p>Frame ingest is turn-scoped (spec D8): one frame per turn, and an
 * {@code image} commit needs audio recorded after the turn boundary. The frame itself is
 * not held here — it reaches the bridge as it arrives and an uncommitted one is the
 * bridge's pending image; only the two counters live with the socket.
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
    /**
     * The phone's AR mode (spec X17): declared before or after {@code hello}, remembered
     * for the socket's life and seeded into the bridge's own {@code hello}. It gates the
     * bridge's commit grace (spec D4) and changes no other frame.
     */
    private volatile boolean arOn;
    /** One frame per turn: set when a frame is accepted, cleared at the turn boundary. */
    private boolean frameSeenThisTurn;
    /** PCM bytes sent to the provider since the turn boundary: an image commit needs some. */
    private long audioBytesSinceCommit;
    private volatile ScheduledFuture<?> helloDeadline;
    /**
     * Gated tool calls waiting for the operator, by approval id. Remove-and-get is atomic,
     * so an approval decided twice re-executes nothing.
     */
    private final Map<UUID, LivePendingToolCall> pendingCalls = new ConcurrentHashMap<>();

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

    /**
     * Forgets the run slot when it still holds {@code runId}, so the next turn's first
     * tool call starts its own row. Conditional on the id: a settle for an older turn's
     * run must not drop the row the current turn already created.
     *
     * @return true when this call cleared the slot
     */
    boolean clearRunSlot(UUID runId) {
        synchronized (this) {
            if (runId != null && runId.equals(this.runId)) {
                this.runId = null;
                return true;
            }
            return false;
        }
    }

    public LiveBridge bridge() {
        return bridge;
    }

    public void bridge(LiveBridge bridge) {
        this.bridge = bridge;
    }

    /** Holds the gated call until the operator's decision arrives. */
    void pendingCall(LivePendingToolCall call) {
        if (call != null && call.approvalId() != null) {
            pendingCalls.put(call.approvalId(), call);
        }
    }

    /**
     * Takes the call for this approval, if this socket holds it. Atomic by design: an
     * approval decided twice — a replayed event, two listeners — executes the tool once.
     */
    LivePendingToolCall takePendingCall(UUID approvalId) {
        return approvalId == null ? null : pendingCalls.remove(approvalId);
    }

    /** Drops every call this socket holds, returning them so their parks can be released. */
    List<LivePendingToolCall> takePendingCalls() {
        List<LivePendingToolCall> calls = new ArrayList<>();
        for (UUID approvalId : List.copyOf(pendingCalls.keySet())) {
            LivePendingToolCall call = pendingCalls.remove(approvalId);
            if (call != null) {
                calls.add(call);
            }
        }
        return calls;
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

    /** The phone's AR mode; false until it says otherwise (spec X17, D5). */
    public boolean arOn() {
        return arOn;
    }

    public void arOn(boolean arOn) {
        this.arOn = arOn;
    }

    /**
     * Claims this turn's one frame (spec D8). Atomic with the check, so two frames racing
     * in from the phone cannot both be told they are the first.
     *
     * @return true when this frame is the turn's first; false when one already stands
     */
    synchronized boolean claimFrame() {
        if (frameSeenThisTurn) {
            return false;
        }
        frameSeenThisTurn = true;
        return true;
    }

    /**
     * Gives back the turn's frame claim when the frame never reached the bridge. The
     * phone's next frame is then a retry, not a second frame, and a later commit cannot
     * lean on a frame that stayed in Java.
     */
    synchronized void releaseFrame() {
        frameSeenThisTurn = false;
    }

    /** Counts PCM actually handed to the bridge: audio the provider can commit. */
    synchronized void audioSent(int bytes) {
        audioBytesSinceCommit += bytes;
    }

    /**
     * Reads the turn's audio and starts the next turn in one step, so a frame racing in
     * from the phone cannot land between the read and the reset.
     *
     * @return true when audio reached the provider during the turn being closed
     */
    synchronized boolean closeTurn() {
        boolean audioSeen = audioBytesSinceCommit > 0;
        beginTurn();
        return audioSeen;
    }

    /**
     * Marks a turn boundary — {@code hello}, the bridge's {@code speech_end} or the phone's
     * {@code commit}: the next frame belongs to the next turn and an image commit needs
     * fresh audio.
     */
    synchronized void beginTurn() {
        frameSeenThisTurn = false;
        audioBytesSinceCommit = 0;
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
