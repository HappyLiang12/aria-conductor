package io.aria.conductor.execution.adk.opencode;

import io.aria.conductor.common.event.RunProgressEvent;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * S9: progress pump for the OpenCode provider. While {@code executeTask} blocks
 * on the synchronous {@code sendMessage}, this virtual thread polls
 * {@code GET /session/:id/message} and streams NEW parts as
 * {@link RunProgressEvent}s so the dashboard can watch the agent think and act
 * in (near) real time.
 *
 * <p>Safety contract:
 * <ul>
 *   <li>poll failures degrade (warn + exponential backoff, cap 30s) — never thrown;</li>
 *   <li>sink (event publisher) exceptions are swallowed — the pump survives;</li>
 *   <li>consecutive THINKING parts within one poll batch are coalesced into a
 *       single event (rate limiting); TOOL_* events are never coalesced;</li>
 *   <li>watermark reset (snapshot shrink) replays at most the latest 10 parts;</li>
 *   <li>{@link #stop()} is idempotent and halts emission.</li>
 * </ul>
 */
@Slf4j
public class OpenCodeProgressPump {

    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(2);
    public static final Duration DEFAULT_COALESCE_WINDOW = Duration.ofMillis(400);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);
    private static final int RESET_REPLAY_LIMIT = 10;

    private final OpenCodeHttpClient client;
    private final String sessionId;
    private final UUID runId;
    private final UUID agentId;
    private final Consumer<RunProgressEvent> sink;
    private final Duration pollInterval;
    @SuppressWarnings("unused")
    private final Duration coalesceWindow; // coalescing is per-batch; kept for config symmetry
    private final AtomicLong seq = new AtomicLong();

    private volatile boolean running;
    private Thread worker;

    public OpenCodeProgressPump(OpenCodeHttpClient client, String sessionId, UUID runId, UUID agentId,
                                Consumer<RunProgressEvent> sink) {
        this(client, sessionId, runId, agentId, sink, DEFAULT_POLL_INTERVAL, DEFAULT_COALESCE_WINDOW);
    }

    public OpenCodeProgressPump(OpenCodeHttpClient client, String sessionId, UUID runId, UUID agentId,
                                Consumer<RunProgressEvent> sink, Duration pollInterval, Duration coalesceWindow) {
        this.client = client;
        this.sessionId = sessionId;
        this.runId = runId;
        this.agentId = agentId;
        this.sink = sink;
        this.pollInterval = pollInterval;
        this.coalesceWindow = coalesceWindow;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        worker = Thread.ofVirtual().name("oc-progress-pump-" + runId).start(this::loop);
    }

    public synchronized void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }

    private void loop() {
        Duration backoff = pollInterval;
        List<Integer> watermark = new ArrayList<>();
        while (running) {
            try {
                List<OpenCodeHttpClient.MessageSnapshot> snaps = client.listMessages(sessionId);
                backoff = pollInterval;
                for (RunProgressEvent ev : diff(snaps, watermark)) {
                    emit(ev);
                }
            } catch (Exception e) {
                log.warn("OpenCode progress pump poll failed for run {} (degrading): {}", runId, e.getMessage());
                backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
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
     * Diff the snapshot against the per-message part watermark; returns events
     * for newly-seen parts only, with consecutive THINKING coalesced.
     */
    List<RunProgressEvent> diff(List<OpenCodeHttpClient.MessageSnapshot> snaps, List<Integer> watermark) {
        // An empty snapshot means the client degraded (transient failure returns
        // List.of()) — treat it as "no data", never as a shrink, so a hiccup does
        // not reset the watermark and replay already-emitted parts.
        boolean reset = !snaps.isEmpty() && snaps.size() < watermark.size();
        if (reset) {
            watermark.clear();
        }
        List<RunProgressEvent> candidates = new ArrayList<>();
        for (int mi = 0; mi < snaps.size(); mi++) {
            List<OpenCodeHttpClient.PartSnapshot> parts = snaps.get(mi).parts();
            int seen = mi < watermark.size() ? watermark.get(mi) : 0;
            for (int pi = seen; pi < parts.size(); pi++) {
                candidates.add(toEvent(parts.get(pi)));
            }
            while (watermark.size() <= mi) {
                watermark.add(0);
            }
            watermark.set(mi, parts.size());
        }
        if (reset && candidates.size() > RESET_REPLAY_LIMIT) {
            candidates = new ArrayList<>(candidates.subList(candidates.size() - RESET_REPLAY_LIMIT, candidates.size()));
        }
        return coalesce(candidates);
    }

    private List<RunProgressEvent> coalesce(List<RunProgressEvent> batch) {
        List<RunProgressEvent> out = new ArrayList<>();
        RunProgressEvent pendingThinking = null;
        for (RunProgressEvent ev : batch) {
            if (ev.getKind() == RunProgressEvent.Kind.THINKING) {
                pendingThinking = ev; // last-write-wins within a run of thinking
            } else {
                if (pendingThinking != null) {
                    out.add(assignSeq(pendingThinking));
                    pendingThinking = null;
                }
                out.add(assignSeq(ev));
            }
        }
        if (pendingThinking != null) {
            out.add(assignSeq(pendingThinking));
        }
        return out;
    }

    private RunProgressEvent assignSeq(RunProgressEvent ev) {
        return new RunProgressEvent(this, ev.getRunId(), ev.getAgentId(), ev.getIteration(),
                ev.getKind(), ev.getContent(), ev.getToolName(), seq.incrementAndGet());
    }

    private RunProgressEvent toEvent(OpenCodeHttpClient.PartSnapshot p) {
        String type = p.type() != null ? p.type() : "";
        return switch (type) {
            case "reasoning", "text" -> new RunProgressEvent(this, runId, agentId, 0,
                    RunProgressEvent.Kind.THINKING, p.text(), null, 0);
            case "tool" -> {
                boolean completed = "completed".equals(p.state());
                yield new RunProgressEvent(this, runId, agentId, 0,
                        completed ? RunProgressEvent.Kind.TOOL_RESULT : RunProgressEvent.Kind.TOOL_CALL,
                        completed ? (p.text() != null ? p.text() : "") : "",
                        p.toolName(), 0);
            }
            case "error" -> new RunProgressEvent(this, runId, agentId, 0,
                    RunProgressEvent.Kind.ERROR, p.text(), null, 0);
            default -> new RunProgressEvent(this, runId, agentId, 0,
                    RunProgressEvent.Kind.STATUS, type, null, 0);
        };
    }

    private void emit(RunProgressEvent ev) {
        try {
            sink.accept(ev);
        } catch (Exception e) {
            log.warn("OpenCode progress sink failed for run {} (pump continues): {}", runId, e.getMessage());
        }
    }
}
