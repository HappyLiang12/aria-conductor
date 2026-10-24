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
 * {@code GET /session/:id/message} and streams the assistant's parts so the
 * dashboard can watch the run think and act in (near) real time.
 *
 * <p><strong>A row is an observation of a part, and the part is the identity.</strong>
 * The opencode message list grows a part <em>in place</em>: one text part whose
 * {@code text} gets longer, one tool part whose {@code state.output} only arrives when
 * the tool finishes. A watermark of part <em>counts</em> therefore emitted such a part
 * once — with whatever it held at that poll — and dropped every later growth, so a long
 * answer stayed half-shown until {@code run.completed} (issue #103). The pump now keeps
 * one tracked entry per part and re-emits it when it changes.
 *
 * <p>Emit policy (what bounds the row volume):
 * <ul>
 *   <li>one part produces at most one observation per poll, and only when its
 *       observed state (type, text, tool name, status, output) differs from the state
 *       last streamed for it — unchanged content never re-emits, so a run that thinks
 *       for twenty polls at the 2s default still costs one row;</li>
 *   <li>a part that reached its final status is streamed once more at that status, so
 *       the row the operator keeps is the finished one;</li>
 *   <li>a re-emission carries the SAME {@code partId} with a strictly greater {@code
 *       seq}: {@code seq} is the order of observations, never a part counter, and the
 *       consumer merges on {@code partId}.</li>
 * </ul>
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
 *   <li>an empty snapshot is never a shrink: it neither resets the tracked parts
 *       nor replays them;</li>
 *   <li>a real snapshot shrink replays at most the latest 10 parts;</li>
 *   <li>sink exceptions are swallowed — the pump survives;</li>
 *   <li>only assistant activity is streamed: a message whose {@code info.role}
 *       is not {@code assistant} (the operator's own user turn included) produces
 *       no fragment, while its parts are still tracked so they are never
 *       reconsidered;</li>
 *   <li>a part that carries nothing an operator can read is never emitted: a
 *       blank reasoning/text part is dropped, and the {@code step-start} /
 *       {@code step-finish} turn markers are skipped rather than shown as a
 *       STATUS row of their own name; a dropped part burns no seq;</li>
 *   <li>a reasoning part is THINKING and an assistant text part is ANSWER —
 *       the model's reply is never labelled its thinking;</li>
 *   <li>consecutive THINKING parts within one poll batch are coalesced into a
 *       single fragment (last wins); ANSWER, TOOL_* and STATUS fragments are
 *       never coalesced; a coalesced-away part is still tracked, so it does not
 *       reappear on the next poll;</li>
 *   <li>the part tracking and seq live in a session-scoped {@link ProgressCursor}
 *       and are read-modified under one monitor, so consecutive prompts of one
 *       native session neither replay an earlier turn, restart seq at 1, nor
 *       interleave a baseline seed into a diff;</li>
 *   <li>seq is monotonic across batches;</li>
 *   <li>{@link #stop()} is idempotent, halts emission, and joins the worker
 *       within a bounded budget so the cursor is settled when it returns; the
 *       sink is never called under the emission lock, so a slow persistence
 *       write costs the halt at most that budget;</li>
 *   <li>{@link #drain()} performs at most {@value #DRAIN_ATTEMPTS} reads, emits
 *       through this same part-keyed cursor and sink (a part already streamed at
 *       its final content is not streamed twice), stops as soon as the turn's
 *       assistant message carries no unfinished text-bearing part, and returns
 *       instead of becoming a second polling loop;</li>
 *   <li>{@link #drain()} on a halted (or never started) pump performs no read at
 *       all and emits nothing: a cancelled turn stays silent, which is a #111
 *       acceptance rule, not a nice-to-have;</li>
 *   <li>{@link #drain()} never throws: a degraded attempt is retried inside the
 *       same bound and does not feed the poll loop's stall counters, because those
 *       count consecutive LOOP polls.</li>
 * </ul>
 *
 * <p><strong>What the reviewed core actually does (opencode 1.18.15, live HOST and
 * SANDBOX probes).</strong> {@code GET /session/:id/message} is NOT a token stream.
 * While the synchronous prompt is in flight the assistant message appears first with
 * zero parts, then as a skeleton whose reasoning part carries an empty {@code text};
 * that blank stayed blank for seven consecutive polls and then jumped in ONE step to
 * the finished 5 594-character part. The assistant {@code text} part only becomes
 * final at/after the {@code POST /message} returns. So the live polls can legitimately
 * see nothing readable, and {@link #drain()} exists to read once more at that moment —
 * it is the reason a HOST turn costs a handful of rows rather than one row per token.
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

    /**
     * How many {@code listMessages} reads {@link #drain()} may make, at most. Two,
     * justified by the observed cadence rather than tuned blind: the core writes a part
     * in ONE step at its completion and the POST has already returned when the drain
     * runs, so the first read normally lands the terminal content. When it does not, the
     * single plausible cause is the list route being one write behind the finished turn
     * (or that read degrading), and one retry covers exactly that lag. A third attempt
     * would be a second polling loop, which this pump deliberately is not: its own loop
     * already covers everything a longer wait could learn.
     */
    static final int DRAIN_ATTEMPTS = 2;

    /**
     * One streamed progress fragment. {@code partId} is the native identity of the
     * part this fragment observed (null when the core reported none, in which case
     * {@code seq} is the only identity a consumer has); {@code iteration} is 0 while
     * the core reports none.
     */
    public record ProgressFragment(RunProgressEvent.Kind kind, String content, String toolName,
            long seq, int iteration, String partId) {
    }

    /**
     * Session-scoped progress cursor: the parts already streamed and the seq handed
     * out. One instance is shared by the pumps of consecutive prompts of the same
     * native session, so the tracked parts stop an earlier turn (or an earlier
     * snapshot of the same part) from being re-emitted and the seq keeps increasing so
     * the dashboard's (runId, seq) dedupe key never collides. A pump never clears it;
     * a new session starts with a fresh cursor.
     *
     * <p>State is one {@link PartRecord} per part, in snapshot order, grouped by the
     * message that carries it: the group list is what makes a real snapshot shrink
     * detectable, and the per-part signature is what makes growth visible. Every
     * writer (a pump's diff, a later prompt's baseline seed) holds this instance's
     * monitor for the whole read-modify-write, so the two can never interleave into a
     * hybrid view.
     */
    public static final class ProgressCursor {
        private final List<List<PartRecord>> tracks = new ArrayList<>();
        private final AtomicLong seq = new AtomicLong();

        /**
         * Seeds the tracked parts from a pre-prompt baseline snapshot. Called before
         * the prompt's pump starts, so a turn the previous pump never observed
         * (a very fast turn, or a first poll that answered before the assistant
         * message was listed) is still known to the next prompt and is never
         * replayed. A successful empty baseline cannot clear what is already tracked:
         * an empty list before a prompt does not prove the native session forgot its
         * earlier messages.
         */
        public synchronized void seedFrom(List<OpenCodeHttpClient.MessageSnapshot> baseline) {
            if (baseline.isEmpty() && !tracks.isEmpty()) {
                return;
            }
            tracks.clear();
            for (OpenCodeHttpClient.MessageSnapshot message : baseline) {
                tracks.add(trackMessage(message));
            }
        }

        /** Read-only: the seq this cursor has handed out so far. */
        synchronized long sequenceValue() {
            return seq.get();
        }

        /** Read-only: how many messages the cursor currently tracks. */
        synchronized int trackedMessageCount() {
            return tracks.size();
        }

        /** Read-only: how many individual parts the cursor currently tracks. */
        synchronized int trackedPartCount() {
            return tracks.stream().mapToInt(List::size).sum();
        }

        /** Replaces one message's tracked parts with the snapshot's current state. */
        private List<PartRecord> trackMessage(OpenCodeHttpClient.MessageSnapshot message) {
            List<PartRecord> records = new ArrayList<>(message.parts().size());
            for (OpenCodeHttpClient.PartSnapshot part : message.parts()) {
                records.add(new PartRecord(part.id(), signatureOf(part)));
            }
            return records;
        }
    }

    /**
     * What the pump last saw of one part: its native id and the projection of every
     * member the operator can read (type, text, tool name, status, output). Equal
     * signature means the part says exactly what the pump already streamed, so it is
     * not streamed again; a different signature — including a status that only changed
     * because the part finished — is a new observation. The raw members are what a
     * baseline seed records too, so a part the pump never emitted is still "known".
     */
    record PartRecord(String partId, String signature) {
    }

    /** The observable projection of one part; two parts stream alike iff this matches. */
    private static String signatureOf(OpenCodeHttpClient.PartSnapshot part) {
        return String.join("\u0000",
                nullToEmpty(part.type()), nullToEmpty(part.text()), nullToEmpty(part.toolName()),
                nullToEmpty(part.state()), nullToEmpty(part.output()));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
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
     * Diffs against the session cursor's tracked parts. The whole read-modify-write
     * (parts and seq) runs under the cursor monitor, so it cannot interleave with
     * another writer of the same session cursor.
     */
    List<ProgressFragment> diff(List<OpenCodeHttpClient.MessageSnapshot> snaps) {
        synchronized (cursor) {
            return coalesce(observe(snaps, cursor.tracks));
        }
    }

    /**
     * Compares the snapshot with the per-message part records and returns the
     * un-numbered observations that are new: a part whose readable state changed, or
     * that appeared at all, of an assistant message only (blank text and turn markers
     * are skipped and burn no seq). Consecutive THINKING coalescing happens later, in
     * {@link #coalesce}, within this one batch.
     *
     * <p>Every part examined is recorded, whether or not it was emitted: a
     * non-assistant message is judged once and never reconsidered, a skipped part
     * stays skipped until its own state changes, and a THINKING part that a later
     * thinking part coalesced away does not reappear on the next poll. That is also
     * what bounds the row volume — a part can add at most one row per poll, and only
     * if it actually said something new.
     */
    private List<ProgressFragment> observe(List<OpenCodeHttpClient.MessageSnapshot> snaps,
            List<List<PartRecord>> tracks) {
        // An empty snapshot means either "no data yet" or a degraded poll — both
        // are "no data", never a shrink, so a hiccup does not forget the tracked
        // parts and replay what was already streamed.
        boolean reset = !snaps.isEmpty() && snaps.size() < tracks.size();
        if (reset) {
            tracks.clear();
        }
        List<ProgressFragment> candidates = new ArrayList<>();
        for (int mi = 0; mi < snaps.size(); mi++) {
            OpenCodeHttpClient.MessageSnapshot message = snaps.get(mi);
            List<OpenCodeHttpClient.PartSnapshot> parts = message.parts();
            List<PartRecord> previous = mi < tracks.size() ? tracks.get(mi) : List.of();
            boolean assistant = isAssistantActivity(message);
            if (parts.size() > previous.size() && !assistant) {
                log.debug("OpenCode progress pump of run {} skips message {} of role '{}':"
                        + " only assistant activity is progress", runId, message.id(), message.role());
            }
            List<PartRecord> records = new ArrayList<>(previous);
            for (int pi = 0; pi < parts.size(); pi++) {
                OpenCodeHttpClient.PartSnapshot part = parts.get(pi);
                String signature = signatureOf(part);
                PartRecord known = pi < records.size() ? records.get(pi) : null;
                boolean changed = known == null
                        || !signature.equals(known.signature())
                        || !Objects.equals(part.id(), known.partId());
                if (pi < records.size()) {
                    records.set(pi, new PartRecord(part.id(), signature));
                } else {
                    records.add(new PartRecord(part.id(), signature));
                }
                if (!changed || !assistant) {
                    // Unchanged content, or a part of a message that is not the
                    // assistant's: tracked, never streamed.
                    continue;
                }
                ProgressFragment fragment = toFragment(part);
                if (fragment != null) {
                    candidates.add(fragment);
                }
            }
            if (mi < tracks.size()) {
                tracks.set(mi, records);
            } else {
                tracks.add(records);
            }
        }
        if (reset && candidates.size() > RESET_REPLAY_LIMIT) {
            candidates = new ArrayList<>(
                    candidates.subList(candidates.size() - RESET_REPLAY_LIMIT, candidates.size()));
        }
        return candidates;
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
                cursor.seq.incrementAndGet(), fragment.iteration(), fragment.partId());
    }

    /**
     * Maps one part onto a fragment carrying that part's native id, or {@code null}
     * when the part carries nothing an operator can read and is skipped. A skipped
     * part is still tracked (it is never reconsidered until its own state changes) and
     * burns no seq.
     *
     * <p>The part id is the consumer's merge key: the same part growing between two
     * polls yields two fragments with this same id and different seq, and the drawer
     * updates one line instead of appending a second one. A core that reports no id
     * yields a null id and the consumer falls back to seq — the pump does not invent an
     * identity the wire never sent.
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
                    : new ProgressFragment(RunProgressEvent.Kind.THINKING, p.text(), null, 0, 0, p.id());
            case "text" -> blank(p.text()) ? null
                    : new ProgressFragment(RunProgressEvent.Kind.ANSWER, p.text(), null, 0, 0, p.id());
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
                        content, p.toolName(), 0, 0, p.id());
            }
            case "error" ->
                    new ProgressFragment(RunProgressEvent.Kind.ERROR, p.text(), null, 0, 0, p.id());
            default -> TURN_BOUNDARY_MARKERS.contains(type) ? null
                    : new ProgressFragment(RunProgressEvent.Kind.STATUS, type, null, 0, 0, p.id());
        };
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    /**
     * One final, synchronous poll + diff + emit for the turn that just finished, called
     * by the owning session after {@code POST /session/:id/message} returned and before
     * {@link #stop()}.
     *
     * <p>Why it must exist: the reviewed core does not stream partials through the
     * message list. It lists the assistant message as a skeleton (a reasoning part whose
     * {@code text} is an empty string, seven polls running) and writes the finished part
     * in one step, and the assistant {@code text} part becomes final only at/after the
     * POST returns. The poll loop is asleep for part of that and is halted in the same
     * {@code finally} the POST returns into, so the terminal parts are reliably missed —
     * the live {@code PROGRESS rows=0} a real HOST turn produced at HEAD 1151df3d.
     *
     * <p>The drain is the same reader with the same state, not a second pipeline: it
     * emits through {@link #diff} and the session cursor, so {@code seq} stays monotonic
     * and a part already streamed at its final content produces no second row. It stops
     * at the {@linkplain #DRAIN_ATTEMPTS bound} or as soon as the turn's assistant
     * message holds nothing unfinished, whichever comes first, and it never throws: this
     * is on the prompt's critical path, and progress is observability.
     *
     * <p>Deliberately a no-op once {@link #stop()} has halted the pump. A cancelled or
     * failed turn is silent, which is issue #111's acceptance rule ("no progress rows
     * after the halt"), and the session therefore calls this only on its success path.
     */
    public void drain() {
        synchronized (emissionLock) {
            if (!running) {
                return;
            }
        }
        try {
            for (int attempt = 1; attempt <= DRAIN_ATTEMPTS; attempt++) {
                OpenCodeHttpClient.MessageListResult result = client.listMessages(sessionId);
                if (result.degraded()) {
                    // The retry belongs to this same bounded attempt budget; the loop's
                    // stall counters stay untouched (they count LOOP polls, and a drain
                    // that cannot read is reported here rather than as a stalled stream).
                    log.warn("OpenCode progress drain of run {} session {} could not read the"
                            + " message list (attempt {}/{} of the terminal drain: {})",
                            runId, sessionId, attempt, DRAIN_ATTEMPTS,
                            result.cause() == null ? "unspecified" : result.cause());
                    continue;
                }
                for (ProgressFragment fragment : diff(result.messages())) {
                    emit(fragment);
                }
                if (terminalContentArrived(result.messages())) {
                    return;
                }
            }
        } catch (Exception e) {
            log.warn("OpenCode progress drain of run {} failed (the run is unaffected): {}",
                    runId, e.getMessage());
        }
    }

    /**
     * Whether the drain may stop early: the assistant message it is waiting for is listed
     * and carries no unfinished text-bearing part. Unfinished here is the observed
     * skeleton state — a reasoning or text part whose {@code text} is still blank, which
     * the core replaces with the whole content in one write — and, before the turn's
     * message appears at all, the absence of any assistant message.
     *
     * <p>A tool part is not part of this predicate on purpose: after a successful POST
     * every tool of the turn has completed, and if the list is behind on a tool part the
     * part-keyed cursor still streams its terminal output whenever it arrives, live or
     * drained.
     */
    private static boolean terminalContentArrived(List<OpenCodeHttpClient.MessageSnapshot> snaps) {
        OpenCodeHttpClient.MessageSnapshot turn = null;
        for (OpenCodeHttpClient.MessageSnapshot message : snaps) {
            if (isAssistantActivity(message)) {
                turn = message;
            }
        }
        if (turn == null) {
            return false;
        }
        for (OpenCodeHttpClient.PartSnapshot part : turn.parts()) {
            String type = part.type() == null ? "" : part.type();
            boolean textBearing = "reasoning".equals(type) || "text".equals(type);
            if (textBearing && blank(part.text())) {
                return false;
            }
        }
        return true;
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
