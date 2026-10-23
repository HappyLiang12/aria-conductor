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
 *   <li>an empty snapshot is never a shrink: it neither resets the watermark
 *       nor replays parts;</li>
 *   <li>a real snapshot shrink replays at most the latest 10 parts;</li>
 *   <li>sink exceptions are swallowed — the pump survives;</li>
 *   <li>consecutive THINKING parts within one poll batch are coalesced into a
 *       single fragment (last wins); TOOL_* fragments are never coalesced;</li>
 *   <li>the part watermark and seq live in a session-scoped
 *       {@link ProgressCursor}, so consecutive prompts of one native session
 *       never replay an earlier turn and never restart seq at 1;</li>
 *   <li>seq is monotonic across batches;</li>
 *   <li>{@link #stop()} is idempotent and no fragment is emitted after it
 *       returns.</li>
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
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);
    private static final int RESET_REPLAY_LIMIT = 10;

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
        public void seedFrom(List<OpenCodeHttpClient.MessageSnapshot> baseline) {
            if (baseline.isEmpty() && !watermark.isEmpty()) {
                return;
            }
            watermark.clear();
            for (OpenCodeHttpClient.MessageSnapshot message : baseline) {
                watermark.add(message.parts().size());
            }
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
    /** Guards the running flag so no sink call can begin after {@link #stop()} returns. */
    private final Object emissionLock = new Object();

    private volatile boolean running;
    private Thread worker;

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
        this.client = Objects.requireNonNull(client, "client");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.runId = Objects.requireNonNull(runId, "runId");
        this.agentId = Objects.requireNonNull(agentId, "agentId");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        this.coalesceWindow = Objects.requireNonNull(coalesceWindow, "coalesceWindow");
        this.cursor = Objects.requireNonNull(cursor, "cursor");
    }

    /** Starts the poll loop; idempotent. */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        worker = Thread.ofVirtual().name("oc-progress-pump-" + runId + "-" + agentId).start(this::loop);
    }

    /**
     * Stops the poll loop; idempotent. When this returns, no further fragment is
     * handed to the sink. The worker's interrupt only shortens its sleep -- the
     * emission guard is what makes the halt prompt-safe.
     */
    public void stop() {
        synchronized (emissionLock) {
            running = false;
        }
        Thread current;
        synchronized (this) {
            current = worker;
            worker = null;
        }
        if (current != null) {
            current.interrupt();
        }
    }

    private void loop() {
        Duration backoff = pollInterval;
        while (running) {
            try {
                OpenCodeHttpClient.MessageListResult result = client.listMessages(sessionId);
                if (result.degraded()) {
                    backoff = nextBackoff(backoff);
                    log.debug("OpenCode progress poll degraded for run {}; backing off to {}ms",
                            runId, backoff.toMillis());
                } else {
                    backoff = pollInterval;
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

    private static Duration nextBackoff(Duration current) {
        Duration doubled = current.multipliedBy(2);
        return doubled.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : doubled;
    }

    /** Diffs against the session cursor's watermark. */
    List<ProgressFragment> diff(List<OpenCodeHttpClient.MessageSnapshot> snaps) {
        return diff(snaps, cursor.watermark);
    }

    /**
     * Diffs the snapshot against a per-message part watermark; returns fragments
     * for newly-seen parts only, with consecutive THINKING coalesced. Exposed
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
            List<OpenCodeHttpClient.PartSnapshot> parts = snaps.get(mi).parts();
            int seen = mi < watermark.size() ? watermark.get(mi) : 0;
            for (int pi = seen; pi < parts.size(); pi++) {
                candidates.add(toFragment(parts.get(pi)));
            }
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

    private List<ProgressFragment> coalesce(List<ProgressFragment> batch) {
        List<ProgressFragment> out = new ArrayList<>();
        ProgressFragment pendingThinking = null;
        for (ProgressFragment fragment : batch) {
            if (fragment.kind() == RunProgressEvent.Kind.THINKING) {
                pendingThinking = fragment; // last-write-wins within a run of thinking
            } else {
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

    private ProgressFragment toFragment(OpenCodeHttpClient.PartSnapshot p) {
        String type = p.type() != null ? p.type() : "";
        return switch (type) {
            case "reasoning", "text" -> new ProgressFragment(RunProgressEvent.Kind.THINKING,
                    p.text(), null, 0, 0);
            case "tool" -> {
                boolean completed = "completed".equals(p.state());
                String content = "";
                if (completed) {
                    // The real wire shape carries a completed tool's output under
                    // state.output; a top-level text member is only a fallback.
                    content = p.output() != null ? p.output() : (p.text() != null ? p.text() : "");
                }
                yield new ProgressFragment(
                        completed ? RunProgressEvent.Kind.TOOL_RESULT : RunProgressEvent.Kind.TOOL_CALL,
                        content, p.toolName(), 0, 0);
            }
            case "error" -> new ProgressFragment(RunProgressEvent.Kind.ERROR, p.text(), null, 0, 0);
            default -> new ProgressFragment(RunProgressEvent.Kind.STATUS, type, null, 0, 0);
        };
    }

    private void emit(ProgressFragment fragment) {
        synchronized (emissionLock) {
            if (!running) {
                return;
            }
            try {
                sink.accept(fragment);
            } catch (Exception e) {
                log.warn("OpenCode progress sink failed for run {} (pump continues): {}",
                        runId, e.getMessage());
            }
        }
    }
}
