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
 * {@code image} commit needs audio recorded after the turn boundary. The frame reaches the
 * bridge as it arrives; its bytes are also kept here — in memory only, and only while the
 * direct locator is on — as the backup's question (spec D6), and they are dropped at the
 * turn end. An uncommitted frame is still the bridge's pending image.
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
    /**
     * This turn's backup-locator candidate (spec D6): the accepted frame's JPEG (stamped
     * with the utterance it was attached in), the last final input transcript, whether an
     * {@code ar_render} already crossed, and whether the one backup of the turn was already
     * claimed. The frame lives here in memory only — it is dropped at the turn end and never
     * written anywhere.
     */
    private byte[] backupFrame;
    private long backupFrameTurn = NO_TURN;
    private String backupTranscript;
    private long backupTurn;
    private boolean backupClaimed;
    private boolean renderThisTurn;
    /** The phone's commit opened the current utterance; its speech_end belongs to it. */
    private boolean committedUtterance;

    /** No utterance: a frame that is not held, or one the turn end already dropped. */
    private static final long NO_TURN = -1;
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

    /**
     * Drops and returns the calls this socket holds for one run. A second gated call parks
     * the same run again — replacing the park the first call registered — so the first call
     * is released through this and must be answered on the bridge.
     */
    List<LivePendingToolCall> takePendingCalls(UUID runId) {
        List<LivePendingToolCall> calls = new ArrayList<>();
        if (runId == null) {
            return calls;
        }
        for (UUID approvalId : List.copyOf(pendingCalls.keySet())) {
            LivePendingToolCall call = pendingCalls.get(approvalId);
            if (call != null && runId.equals(call.runId()) && pendingCalls.remove(approvalId, call)) {
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

    // --- the backup locator's turn (spec D6) ----------------------------------------------

    /**
     * Starts the socket's first backup window ({@code hello}): nothing from an earlier
     * connection may answer it.
     */
    synchronized void beginBackupTurn() {
        backupFrame = null;
        backupFrameTurn = NO_TURN;
        backupTranscript = null;
        backupClaimed = false;
        renderThisTurn = false;
        committedUtterance = false;
        backupTurn++;
    }

    /**
     * The phone's {@code commit} opens the next utterance: its own audio ended, so the
     * frame and the question that follow belong to it. The bridge's {@code speech_end} for
     * the same utterance is consumed by {@link #speechEnded()}.
     */
    synchronized void beginUtterance() {
        openUtterance();
        committedUtterance = true;
    }

    /**
     * The bridge's {@code speech_end}. It opens the next utterance unless the phone's own
     * commit already did: the two signals describe one utterance, and a second boundary
     * would detach the frame the commit's turn carries.
     */
    synchronized void speechEnded() {
        if (committedUtterance) {
            committedUtterance = false;
            return;
        }
        openUtterance();
    }

    private void openUtterance() {
        backupTranscript = null;
        backupClaimed = false;
        renderThisTurn = false;
        backupTurn++;
    }

    /** Keeps the turn's accepted frame for the backup only; nothing else reads it. */
    synchronized void backupFrame(byte[] jpeg) {
        backupFrame = jpeg;
        // The frame answers the utterance it was attached in; a later utterance that sends
        // none of its own must not be answered with this one.
        backupFrameTurn = backupTurn;
        // A new frame is a new question: whatever the last utterance asked does not apply.
        backupTranscript = null;
        renderThisTurn = false;
    }

    /** The turn's last final input transcript (deltas are ignored: the final one replaces). */
    synchronized void backupTranscript(String text) {
        backupTranscript = text;
    }

    /** An {@code ar_render} already crossed this turn: the backup must not add a second one. */
    synchronized void renderForwarded() {
        renderThisTurn = true;
    }

    /**
     * Takes this turn's backup claim, once. Eligible only when a frame attached in the
     * current utterance and a final transcript are both held and no render crossed; a second
     * call (a second turn end, a retry) gets null. The stamp is what keeps a frame from an
     * utterance that never ended from answering a later question.
     */
    synchronized Backup claimBackup() {
        if (backupClaimed || renderThisTurn || backupFrame == null || backupFrameTurn != backupTurn
                || backupTranscript == null || backupTranscript.isBlank()) {
            return null;
        }
        backupClaimed = true;
        return new Backup(backupFrame, backupTranscript, backupTurn);
    }

    /**
     * The claim on forwarding a locator's answer: false when a later utterance has already
     * started (the answer belongs to a turn that is over), when a render already crossed, or
     * when the socket is closed — so a slow answer can never overwrite a later turn's overlay.
     */
    synchronized boolean claimBackupRender(long turn) {
        if (closed() || turn != backupTurn || renderThisTurn) {
            return false;
        }
        renderThisTurn = true;
        return true;
    }

    /** The turn ended: the stored frame and transcript are dropped (spec D6/D8). */
    synchronized void endBackupTurn() {
        backupFrame = null;
        backupFrameTurn = NO_TURN;
        backupTranscript = null;
        renderThisTurn = false;
    }

    /** One turn's backup question: the frame's bytes and the transcript that asked it. */
    record Backup(byte[] frame, String transcript, long turn) {
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
