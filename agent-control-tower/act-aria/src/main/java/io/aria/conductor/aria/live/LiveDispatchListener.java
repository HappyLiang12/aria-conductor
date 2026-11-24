package io.aria.conductor.aria.live;

import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.event.RunIterationEvent;
import io.aria.conductor.common.event.RunProgressEvent;
import io.aria.conductor.common.event.RunStartedEvent;
import io.aria.conductor.common.model.RunStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes a dispatched child run's lifecycle to the socket that approved it (Phase 4, spec
 * 2026-11-25-aria-pa-async-tower-design.md §4.2). Only runs in the dispatch registry are
 * observed: every other run keeps today's behaviour, and the turn's own live run is never
 * registered, so the two vocabularies cannot collide.
 *
 * <p>Progress is a stable sentence and is coalesced to at most one frame per run per
 * second: the engine publishes both an iteration and a streamed fragment for the same
 * step, and the phone's chip needs a heartbeat, not a transcript. No prompt, tool argument
 * or run output ever crosses or is logged.
 */
@Slf4j
@Component
public class LiveDispatchListener {

    /** One progress frame per run per this window. */
    static final long PROGRESS_WINDOW_MS = 1_000;

    private final LiveDispatchService dispatches;
    private final LiveFrames frames;
    private final LiveProperties properties;
    private final Clock clock;
    private final Map<UUID, Instant> lastProgress = new ConcurrentHashMap<>();

    @Autowired
    public LiveDispatchListener(LiveDispatchService dispatches, LiveFrames frames,
                                LiveProperties properties) {
        this(dispatches, frames, properties, Clock.systemUTC());
    }

    LiveDispatchListener(LiveDispatchService dispatches, LiveFrames frames,
                         LiveProperties properties, Clock clock) {
        this.dispatches = dispatches;
        this.frames = frames;
        this.properties = properties;
        this.clock = clock;
    }

    @EventListener
    public void onStarted(RunStartedEvent event) {
        if (emit(event.getRunId(), "started", "running", false)) {
            lastProgress.put(event.getRunId(), clock.instant());
        }
    }

    @EventListener
    public void onIteration(RunIterationEvent event) {
        emit(event.getRunId(), "progress", "iteration " + event.getIteration(), true);
    }

    @EventListener
    public void onProgress(RunProgressEvent event) {
        emit(event.getRunId(), "progress", progressSummary(event), true);
    }

    @EventListener
    public void onCompleted(RunCompletedEvent event) {
        Optional<LiveDispatchService.Dispatch> found = dispatches.find(event.getRunId());
        if (found.isEmpty()) {
            return;
        }
        boolean finished = event.getStatus() == RunStatus.COMPLETED;
        String state = finished ? "finished" : "failed";
        String summary = finished ? "finished" : "failed: " + stableReason(event);
        LiveDispatchService.Dispatch dispatch = found.get();
        frames.toPhone(dispatch.session(),
                LiveFrames.runEvent(event.getRunId(), state, summary, "dispatch"));
        // Phase 4 §4.3: the outcome is spoken by the model, not a phone TTS. A failure is
        // an interrupt; a completion waits for the answer already in flight.
        frames.toBridge(dispatch.session(), LiveFrames.tower(
                outcomeText(dispatch.agentName(), finished, event),
                finished ? "idle" : "interrupt"));
        dispatches.remove(event.getRunId());
        lastProgress.remove(event.getRunId());
        log.debug("live dispatch settled: run={} state={}", event.getRunId(), state);
    }

    /** @return true when the frame crossed; false for an unknown run or a coalesced tick */
    private boolean emit(UUID runId, String state, String summary, boolean coalesce) {
        Optional<LiveDispatchService.Dispatch> found = dispatches.find(runId);
        if (found.isEmpty()) {
            return false;
        }
        if (coalesce) {
            Instant now = clock.instant();
            Instant previous = lastProgress.put(runId, now);
            if (previous != null && now.toEpochMilli() - previous.toEpochMilli() < PROGRESS_WINDOW_MS) {
                return false;
            }
        }
        frames.toPhone(found.get().session(), LiveFrames.runEvent(runId, state, summary, "dispatch"));
        return true;
    }

    /** A payload-free progress line: the kind, never the fragment's text or a tool's args. */
    private static String progressSummary(RunProgressEvent event) {
        return switch (event.getKind()) {
            case TOOL_CALL, TOOL_RESULT -> event.getToolName() == null || event.getToolName().isBlank()
                    ? "tool" : "tool: " + event.getToolName();
            case STATUS -> "running";
            default -> "progress";
        };
    }

    /**
     * The spoken outcome: a bounded, payload-free sentence. A completion carries a short
     * preview (the same bound rule as a tool result, never a prompt or a path); a failure
     * carries its stable code, never the operator-facing message.
     */
    private String outcomeText(String agentName, boolean finished, RunCompletedEvent event) {
        String who = agentName == null || agentName.isBlank() ? "" : agentName + " ";
        if (!finished) {
            return "[tower] " + who + "失敗：" + stableReason(event);
        }
        String preview = preview(event.getFinalOutput());
        return preview.isEmpty()
                ? "[tower] " + who + "已完成"
                : "[tower] " + who + "已完成：" + preview;
    }

    private String preview(String output) {
        if (output == null) {
            return "";
        }
        String compact = output.replaceAll("\\s+", " ").trim();
        int max = properties.dispatchPreviewChars();
        return compact.length() <= max ? compact : compact.substring(0, max);
    }

    /** The stable failure identity; the operator-facing message is never forwarded. */
    private static String stableReason(RunCompletedEvent event) {
        String code = event.getErrorCode();
        if (code != null && !code.isBlank()) {
            return code;
        }
        return event.getStatus() == null ? "failed" : event.getStatus().name().toLowerCase();
    }
}
