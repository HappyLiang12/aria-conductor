package io.aria.conductor.execution.runtime.progress;

import io.aria.conductor.common.event.RunProgressEvent;
import io.aria.conductor.execution.adk.opencode.OpenCodeHttpClient;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Progress pump for a run-owned OpenCode session: while the prompt blocks on
 * the synchronous {@code sendPrompt}, this virtual thread polls
 * {@code GET /session/:id/message} and streams NEW parts so the dashboard can
 * watch the run think and act in (near) real time.
 *
 * <p>Safety contract:
 * <ul>
 *   <li>a degraded poll (non-2xx, I/O failure, malformed body — the client's
 *       {@code degraded} flag) backs off exponentially, cap 30s, and never
 *       throws; a successful empty list is ordinary "no data yet" and resets
 *       the backoff;</li>
 *   <li>a streak of {@value #DEGRADED_STALL_THRESHOLD} consecutive degraded
 *       polls is a stalled progress stream and raises exactly one WARN (runId,
 *       session, last cause, current backoff) plus a countable signal; a
 *       successful poll re-arms it, so a later stall warns again;</li>
 *   <li>an empty snapshot is never a shrink: it neither resets the watermark
 *       nor replays parts;</li>
 *   <li>a real snapshot shrink replays at most the latest 10 parts;</li>
 *   <li>sink exceptions are swallowed — the pump survives;</li>
 *   <li>only assistant activity is streamed: a message whose {@code info.role}
 *       is not {@code assistant} (the operator's own user turn included) produces
 *       no fragment, while its parts still advance the watermark so it is never
 *       reconsidered;</li>
 *   <li>a part that carries nothing an operator can read is never emitted: a
 *       blank reasoning/text part is dropped, and the {@code step-start} /
 *       {@code step-finish} turn markers are skipped rather than shown as a
 *       STATUS row of their own name; a dropped part burns no seq;</li>
 *   <li>a reasoning part is THINKING and an assistant text part is ANSWER —
 *       the model's reply is never labelled its thinking;</li>
 *   <li>consecutive THINKING parts within one poll batch are coalesced into a
 *       single fragment (last wins); ANSWER, TOOL_* and STATUS fragments are
 *       never coalesced;</li>
 *   <li>the part watermark and seq live in a session-scoped
 *       {@link ProgressCursor} and are read-modified under one monitor, so
 *       consecutive prompts of one native session neither replay an earlier
 *       turn, restart seq at 1, nor interleave a baseline seed into a diff;</li>
 *   <li>seq is monotonic across batches;</li>
 *   <li>{@link #stop()} is idempotent, halts emission, and joins the worker
 *       within a bounded budget so the cursor is settled when it returns; the
 *       sink is never called under the emission lock, so a slow persistence
 *       write costs the halt at most that budget.</li>
 * </ul>
 *
 * <p>The pump publishes fragments, not domain events: the owning session turns
 * a fragment into one {@code opencode.progress} CoreEvent, so the run identity
 * stays with the layer that owns it.
 */
@Slf4j
public class CoreProgressPump {

    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(2);
    public static final Duration DEFAULT_COALESCE_WINDOW = Duration.ofMillis(400);

    /**
     * How long {@link #stop()} waits for the poll worker to exit. The worker only
     * blocks in a 10s-capped progress poll, the diff section and the sink call, so
     * two seconds bounds the delay a slow persistence write can add to prompt
     * completion; past the budget {@code stop()} returns and says so rather than
     * holding the run's terminal result hostage.
     */
    public static final Duration DEFAULT_STOP_JOIN_TIMEOUT = Duration.ofSeconds(2);

    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);
    private static final int RESET_REPLAY_LIMIT = 10;

    /**
     * Part types that are turn bookkeeping, not activity: the {@code step-start} /
     * {@code step-finish} markers of the live HOST rows (issue #103, seq 2).
     */
    private static final List<String> TURN_BOUNDARY_MARKERS = List.of("step-start", "step-finish");

    /**
     * Consecutive degraded polls that make a stall operator-visible. Three is the
     * smallest number a single transient blip cannot reach: one poll can time out
     * after {@code LIST_MESSAGES_TIMEOUT} (10s) or be answered 5xx by the proxy,
     * and the two fast retries that follow absorb a short hiccup. At the default
     * 2s interval with the 2s-&gt;4s-&gt;8s backoff the WARN lands about 12s into a
     * stall -- far inside a 45-minute run, and never for a single failed poll.
     */
    static final int DEGRADED_STALL_THRESHOLD = 3;

    /** One streamed progress fragment; {@code iteration} is 0 while the core reports none. */
    public record ProgressFragment(RunProgressEvent.Kind kind, String content, String toolName,
            long seq, int iteration) {
    }

    /**
     * Session-scoped progress cursor. One instance is shared by the pumps of
     * consecutive prompts of the same native session: the part watermark stops
     * an earlier turn from being re-emitted, and the seq keeps increasing so the
     * dashboard's (runId, seq) dedupe key never collides. A pump never clears
     * it; a new session starts with a fresh cursor.
     *
     * <p>The watermark is a plain list guarded by this instance's monitor: every
     * writer (a pump's diff, a later prompt's baseline seed) holds it for the
     * whole read-modify-write, so the two can never interleave into a hybrid
     * watermark.
     */
    public static final class ProgressCursor {
        private final List<Integer> watermark = new ArrayList<>();
        private final AtomicLong seq = new AtomicLong();

        /**
         * Seeds the watermark from a pre-prompt baseline snapshot. Called before
         * the prompt's pump starts, so a turn the previous pump never observed
         * (a very fast turn, or a first poll that answered before the assistant
         * message was listed) is still known to the next prompt and is never
         * replayed. A successful empty baseline cannot clear an existing
         * watermark: an empty list before a prompt does not prove the native
         * session forgot its earlier messages.
         */
        public synchronized void seedFrom(List<OpenCodeHttpClient.MessageSnapshot> baseline) {
            if (baseline.isEmpty() && !watermark.isEmpty()) {
                return;
            }
            watermark.clear();
            for (OpenCodeHttpClient.MessageSnapshot message : baseline) {
                watermark.add(message.parts().size());
            }
        }

        /** Read-only: the seq this cursor has handed out so far. */
        synchronized long sequenceValue() {
            return seq.get();
        }

        /** Read-only: how many messages the watermark currently tracks. */
        synchronized int watermarkSize() {
            return watermark.size();
        }
    }

    private final OpenCodeHttpClient client;
    private final String sessionId;
    private final UUID runId;
    private final UUID agentId;
    private final Consumer<ProgressFragment> sink;
    private final Duration pollInterval;
    /** Kept for configuration symmetry: coalescing is per poll batch, not wall-clock driven. */
    private final Duration coalesceWindow;
    private final ProgressCursor cursor;
    /** Upper bound on how long {@link #stop()} waits for the poll worker. */
    private final Duration stopJoinTimeout;
    /**
     * Guards the running flag only. A fragment is claimed under it and then
     * handed to the sink outside it, so a slow sink cannot delay {@link #stop()}.
     */
    private final Object emissionLock = new Object();

    private volatile boolean running;
    private Thread worker;
    private volatile long degradedPolls;
    private volatile int consecutiveDegradedPolls;
    private volatile int degradedStallSignals;
    private volatile String lastDegradedCause;

    public CoreProgressPump(OpenCodeHttpClient client, String sessionId, UUID runId, UUID agentId,
            Consumer<ProgressFragment> sink) {
        this(client, sessionId, runId, agentId, sink, DEFAULT_POLL_INTERVAL, DEFAULT_COALESCE_WINDOW,
                new ProgressCursor());
    }

    public CoreProgressPump(OpenCodeHttpClient client, String sessionId, UUID runId, UUID agentId,
            Consumer<ProgressFragment> sink, Duration pollInterval, Duration coalesceWindow) {
        this(client, sessionId, runId, agentId, sink, pollInterval, coalesceWindow,
                new ProgressCursor());
    }

    public CoreProgressPump(OpenCodeHttpClient client, String sessionId, UUID runId, UUID agentId,
            Consumer<ProgressFragment> sink, Duration pollInterval, Duration coalesceWindow,
            ProgressCursor cursor) {
        this(client, sessionId, runId, agentId, sink, pollInterval, coalesceWindow, cursor,
                DEFAULT_STOP_JOIN_TIMEOUT);
    }

    public CoreProgressPump(OpenCodeHttpClient client, String sessionId, UUID runId, UUID agentId,
            Consumer<ProgressFragment> sink, Duration pollInterval, Duration coalesceWindow,
            ProgressCursor cursor, Duration stopJoinTimeout) {
        this.client = Objects.requireNonNull(client, "client");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.runId = Objects.requireNonNull(runId, "runId");
        this.agentId = Objects.requireNonNull(agentId, "agentId");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        this.coalesceWindow = Objects.requireNonNull(coalesceWindow, "coalesceWindow");
        this.cursor = Objects.requireNonNull(cursor, "cursor");
        this.stopJoinTimeout = Objects.requireNonNull(stopJoinTimeout, "stopJoinTimeout");
    }

    /**
     * Cumulative count of degraded polls since the pump was created. Never
     * resets: this is the countable "progress has been unreliable" signal.
     */
    public long degradedPollCount() {
        return degradedPolls;
    }

    /** Consecutive degraded polls right now; zero as soon as one poll succeeds. */
    public int consecutiveDegradedPolls() {
        return consecutiveDegradedPolls;
    }

    /**
     * Number of operator-visible stall signals this pump has raised: one per
     * degraded streak of {@value #DEGRADED_STALL_THRESHOLD}+ consecutive polls.
     */
    public int degradedStallSignals() {
        return degradedStallSignals;
    }

    /** The cause of the most recent degraded poll, or {@code null} while healthy. */
    public String lastDegradedCause() {
        return lastDegradedCause;
    }

    /** Starts the poll loop; idempotent. */
    public synchronized void start() {
        if (running) {
            return;
        }
        Thread stale = worker;
        if (stale != null && stale.isAlive()) {
            // The previous pump outlived its stop budget and still owns the session
            // cursor; a second loop would poll and diff against it concurrently.
            log.warn("OpenCode progress pump of run {} is not restarted: the previous"
                    + " poll worker is still running", runId);
            return;
        }
        running = true;
        worker = Thread.ofVirtual().name("oc-progress-pump-" + runId + "-" + agentId).start(this::loop);
    }

    /**
     * Stops the poll loop; idempotent. When this returns the worker has exited --
     * within {@link #stopJoinTimeout} -- so no further fragment is handed to the
     * sink and the session cursor is settled: a later prompt's baseline seed
     * cannot interleave with a diff of this pump.
     *
     * <p>The interrupt only shortens the poll interval; the join is what makes the
     * halt observable. If the worker does not exit within the budget (a sink that
     * is stuck in a slow insert), {@code stop()} logs a WARN and returns: the run's
     * terminal result is never blocked on progress observability. The remaining
     * fragments of that batch are still dropped, because every emission re-checks
     * the running flag, and an abandoned poll no longer advances the cursor.
     */
    public void stop() {
        Thread current;
        synchronized (this) {
            current = worker;
        }
        synchronized (emissionLock) {
            running = false;
        }
        if (current == null || current == Thread.currentThread()) {
            // Stopping from inside the sink must not wait for itself.
            return;
        }
        current.interrupt();
        try {
            current.join(stopJoinTimeout.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        if (current.isAlive()) {
            log.warn("OpenCode progress poll worker of run {} did not exit within {}ms;"
                    + " a progress fragment may still be delivered after stop() returned",
                    runId, stopJoinTimeout.toMillis());
            return;
        }
        synchronized (this) {
            if (worker == current) {
                worker = null;
            }
        }
    }

    private void loop() {
        Duration backoff = pollInterval;
        while (running) {
            try {
                OpenCodeHttpClient.MessageListResult result = client.listMessages(sessionId);
                if (!running) {
                    // The halt is authoritative: a poll that answered after stop()
                    // neither emits nor consumes the shared cursor, so its parts
                    // stay streamable for the next prompt of the session.
                    return;
                }
                if (result.degraded()) {
                    backoff = nextBackoff(backoff);
                    degrade(result.cause(), backoff);
                } else {
                    backoff = pollInterval;
                    recover();
                    for (ProgressFragment fragment : diff(result.messages())) {
                        emit(fragment);
                    }
                }
            } catch (Exception e) {
                // The client reports degradation through its result flag; this
                // catch is the last line for an unexpected failure and never
                // reaches the prompt path.
                log.warn("OpenCode progress pump poll failed for run {} (degrading): {}",
                        runId, e.getMessage());
                backoff = nextBackoff(backoff);
                degrade(e.getClass().getSimpleName() + ": " + e.getMessage(), backoff);
            }
            if (!running) {
                break;
            }
            try {
                Thread.sleep(backoff.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Records one degraded poll. The per-poll detail stays at debug (a 5xx proxy
     * answers every 2s), but a streak of {@value #DEGRADED_STALL_THRESHOLD}
     * consecutive degraded polls is a stalled progress stream and issue #103's
     * whole point, so it raises exactly one WARN per streak plus a countable
     * signal.
     */
    private void degrade(String cause, Duration backoff) {
        degradedPolls++;
        consecutiveDegradedPolls++;
        lastDegradedCause = cause == null ? "unspecified" : cause;
        log.debug("OpenCode progress poll degraded for run {}; backing off to {}ms",
                runId, backoff.toMillis());
        if (consecutiveDegradedPolls == DEGRADED_STALL_THRESHOLD) {
            degradedStallSignals++;
            log.warn("OpenCode progress stream is stalled for run {} session {}: {} consecutive"
                            + " degraded polls (last cause: {}; backing off to {}ms). The run keeps"
                            + " running; only its live progress is paused until a poll succeeds.",
                    runId, sessionId, consecutiveDegradedPolls, lastDegradedCause, backoff.toMillis());
        }
    }

    /**
     * A successful poll ends the stall: the consecutive counter resets so a later
     * streak warns again. The cumulative counters never reset.
     */
    private void recover() {
        if (consecutiveDegradedPolls >= DEGRADED_STALL_THRESHOLD) {
            log.info("OpenCode progress stream recovered for run {} session {} after {} degraded polls"
                            + " (last cause: {})", runId, sessionId, consecutiveDegradedPolls,
                    lastDegradedCause);
        }
        consecutiveDegradedPolls = 0;
    }

    private static Duration nextBackoff(Duration current) {
        Duration doubled = current.multipliedBy(2);
        return doubled.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : doubled;
    }

    /**
     * Diffs against the session cursor's watermark. The whole read-modify-write
     * (watermark and seq) runs under the cursor monitor, so it cannot interleave
     * with another writer of the same session cursor.
     */
    List<ProgressFragment> diff(List<OpenCodeHttpClient.MessageSnapshot> snaps) {
        synchronized (cursor) {
            return diff(snaps, cursor.watermark);
        }
    }

    /**
     * Diffs the snapshot against a per-message part watermark; returns fragments
     * for the newly-seen parts of assistant messages only (blank text and turn
     * markers skipped), with consecutive THINKING coalesced. Exposed
     * package-private so the reset/replay cap is testable without timing.
     */
    List<ProgressFragment> diff(List<OpenCodeHttpClient.MessageSnapshot> snaps, List<Integer> watermark) {
        // An empty snapshot means either "no data yet" or a degraded poll — both
        // are "no data", never a shrink, so a hiccup does not reset the
        // watermark and replay already-emitted parts.
        boolean reset = !snaps.isEmpty() && snaps.size() < watermark.size();
        if (reset) {
            watermark.clear();
        }
        List<ProgressFragment> candidates = new ArrayList<>();
        for (int mi = 0; mi < snaps.size(); mi++) {
            OpenCodeHttpClient.MessageSnapshot message = snaps.get(mi);
            List<OpenCodeHttpClient.PartSnapshot> parts = message.parts();
            int seen = mi < watermark.size() ? watermark.get(mi) : 0;
            boolean assistant = isAssistantActivity(message);
            if (parts.size() > seen && !assistant) {
                log.debug("OpenCode progress pump of run {} skips message {} of role '{}':"
                        + " only assistant activity is progress", runId, message.id(), message.role());
            }
            if (assistant) {
                for (int pi = seen; pi < parts.size(); pi++) {
                    ProgressFragment fragment = toFragment(parts.get(pi));
                    if (fragment != null) {
                        candidates.add(fragment);
                    }
                }
            }
            // The watermark tracks every message's parts, a skipped one included,
            // so a non-assistant message is judged once and never reconsidered on
            // a later poll.
            while (watermark.size() <= mi) {
                watermark.add(0);
            }
            watermark.set(mi, parts.size());
        }
        if (reset && candidates.size() > RESET_REPLAY_LIMIT) {
            candidates = new ArrayList<>(
                    candidates.subList(candidates.size() - RESET_REPLAY_LIMIT, candidates.size()));
        }
        return coalesce(candidates);
    }

    /**
     * Whether one listed message is the assistant's own activity (issue #103).
     *
     * <p>opencode's message list carries the operator's user message next to the
     * assistant's, and a pump that reads no role streams the operator's own prompt
     * back as the assistant's thinking -- the live HOST row this fix is judged on.
     * Only {@code assistant} streams: any other role, and a message whose core
     * left the member out, is not provably the assistant's activity, so it is not
     * presented as such. Fail-closed is the deliberate choice: the truthful
     * failure mode of an unreadable role is a quiet stream the degraded-poll story
     * already surfaces, not a wrong answer about who spoke.
     */
    private static boolean isAssistantActivity(OpenCodeHttpClient.MessageSnapshot message) {
        return "assistant".equalsIgnoreCase(message.role());
    }

    private List<ProgressFragment> coalesce(List<ProgressFragment> batch) {
        List<ProgressFragment> out = new ArrayList<>();
        ProgressFragment pendingThinking = null;
        for (ProgressFragment fragment : batch) {
            if (fragment.kind() == RunProgressEvent.Kind.THINKING) {
                pendingThinking = fragment; // last-write-wins within a run of thinking
            } else {
                // ANSWER is never coalesced: dropping an answer fragment because a
                // later one arrived would lose what the model actually said.
                if (pendingThinking != null) {
                    out.add(assignSeq(pendingThinking));
                    pendingThinking = null;
                }
                out.add(assignSeq(fragment));
            }
        }
        if (pendingThinking != null) {
            out.add(assignSeq(pendingThinking));
        }
        return out;
    }

    private ProgressFragment assignSeq(ProgressFragment fragment) {
        return new ProgressFragment(fragment.kind(), fragment.content(), fragment.toolName(),
                cursor.seq.incrementAndGet(), fragment.iteration());
    }

    /**
     * Maps one part onto a fragment, or {@code null} when the part carries nothing
     * an operator can read and is skipped. A skipped part still advances the
     * watermark (it is never reconsidered) and burns no seq.
     *
     * <p>Skipped by design:
     * <ul>
     *   <li>a blank reasoning/text part — an empty row is not progress;</li>
     *   <li>the {@code step-start}/{@code step-finish} markers — a part-type
     *   sentinel whose only content is its own name ("step-start" on the
     *   operator's screen, the live seq 2) says nothing a turn boundary is not
     *   already told by {@code run.iteration} and {@code run.completed}, so the
     *   marker is skipped rather than rewritten as a readable STATUS.</li>
     * </ul>
     * An unknown part type still becomes STATUS: a core that grows a new part is
     * then visible instead of silently invisible, which is the opposite of the
     * above (a known marker with no content has nothing to show).
     */
    private ProgressFragment toFragment(OpenCodeHttpClient.PartSnapshot p) {
        String type = p.type() != null ? p.type() : "";
        return switch (type) {
            case "reasoning" -> blank(p.text()) ? null
                    : new ProgressFragment(RunProgressEvent.Kind.THINKING, p.text(), null, 0, 0);
            case "text" -> blank(p.text()) ? null
                    : new ProgressFragment(RunProgressEvent.Kind.ANSWER, p.text(), null, 0, 0);
            case "tool" -> {
                boolean completed = "completed".equals(p.state());
                String content = "";
                if (completed) {
                    // The real wire shape carries a completed tool's output under
                    // state.output; a top-level text member is only a fallback.
                    content = p.output() != null ? p.output() : (p.text() != null ? p.text() : "");
                }
                // A tool part is never blank-dropped: its toolName is the content
                // the operator reads ("bash is running", "bash returned nothing").
                yield new ProgressFragment(
                        completed ? RunProgressEvent.Kind.TOOL_RESULT : RunProgressEvent.Kind.TOOL_CALL,
                        content, p.toolName(), 0, 0);
            }
            case "error" -> new ProgressFragment(RunProgressEvent.Kind.ERROR, p.text(), null, 0, 0);
            default -> TURN_BOUNDARY_MARKERS.contains(type) ? null
                    : new ProgressFragment(RunProgressEvent.Kind.STATUS, type, null, 0, 0);
        };
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    /**
     * Hands one fragment to the sink. The lock guards only the halt check, never
     * the sink call: the sink is a synchronous persistence write, and holding the
     * emission lock across it would make {@link #stop()} -- and with it the
     * prompt's terminal completion -- wait on the insert.
     *
     * <p>Ordering does not depend on the lock. This pump's own worker thread is
     * the only caller, so fragments leave in cursor order (single-threaded
     * emitter); a fragment that starts here is finished before the next one is
     * claimed.
     */
    private void emit(ProgressFragment fragment) {
        synchronized (emissionLock) {
            if (!running) {
                return;
            }
        }
        try {
            sink.accept(fragment);
        } catch (Exception e) {
            log.warn("OpenCode progress sink failed for run {} (pump continues): {}",
                    runId, e.getMessage());
        }
    }
}
