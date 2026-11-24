package io.aria.conductor.aria.live;

import io.aria.conductor.agent.dto.RunResponse;
import io.aria.conductor.agent.service.RunService;
import io.aria.conductor.common.model.RunStatus;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The per-socket dispatch registry (Phase 4, spec 2026-11-25-aria-pa-async-tower-design.md):
 * a run started by an approved live write belongs to the socket that approved it, so its
 * lifecycle frames and its outcome travel only there. No persistence: the authoritative row
 * is the run itself; this is the routing table between a child run and one socket.
 *
 * <p>Entries are removed on the run's terminal status and when the socket closes; the
 * timed reminders of Task 6 hang off the {@link Dispatch} entry.
 */
@Slf4j
@Service
public class LiveDispatchService {

    /** One dispatched child run and the socket that owns it. */
    public static class Dispatch {
        private final UUID runId;
        private final LiveSession session;
        private final String agentName;
        private final Instant startedAt;
        /** The one 仲做緊 line was already sent for this run. */
        private volatile boolean reminderSent;
        /** The outcome (tower) was already sent for this run. */
        private volatile boolean towerSent;

        Dispatch(UUID runId, LiveSession session, String agentName, Instant startedAt) {
            this.runId = runId;
            this.session = session;
            this.agentName = agentName;
            this.startedAt = startedAt;
        }

        public UUID runId() {
            return runId;
        }

        public LiveSession session() {
            return session;
        }

        public String agentName() {
            return agentName;
        }

        public Instant startedAt() {
            return startedAt;
        }

        public boolean markReminderSent() {
            if (reminderSent) {
                return false;
            }
            reminderSent = true;
            return true;
        }

        public boolean markTowerSent() {
            if (towerSent) {
                return false;
            }
            towerSent = true;
            return true;
        }
    }

    private final Map<UUID, Dispatch> byRun = new ConcurrentHashMap<>();
    private final Map<UUID, List<ScheduledFuture<?>>> timers = new ConcurrentHashMap<>();
    private final RunService runs;
    private final LiveFrames frames;
    private final LiveProperties properties;
    private final ScheduledExecutorService scheduler;

    @Autowired
    public LiveDispatchService(RunService runs, LiveFrames frames, LiveProperties properties) {
        this(runs, frames, properties, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "aria-live-dispatch");
            thread.setDaemon(true);
            return thread;
        }));
    }

    /** Registry-only form for tests and the router's test seam: no run service, no timers. */
    LiveDispatchService() {
        this(null, null, null, null);
    }

    LiveDispatchService(RunService runs, LiveFrames frames, LiveProperties properties,
                        ScheduledExecutorService scheduler) {
        this.runs = runs;
        this.frames = frames;
        this.properties = properties;
        this.scheduler = scheduler;
    }

    public void register(UUID runId, LiveSession session, String agentName) {
        if (runId == null || session == null) {
            return;
        }
        byRun.put(runId, new Dispatch(runId, session, agentName == null ? "" : agentName, Instant.now()));
        armTimers(runId);
        log.debug("live dispatch registered: run={}", runId);
    }

    /**
     * Phase 4 §4.5: one 仲做緊 line after the reminder window, and a hard timeout that stops
     * the run and reports the failure. Both re-read the run before acting (it may have
     * finished between scheduling and firing) and are cancelled on any removal.
     */
    private void armTimers(UUID runId) {
        if (scheduler == null || runs == null || frames == null || properties == null) {
            return;
        }
        List<ScheduledFuture<?>> armed = new ArrayList<>(2);
        armed.add(scheduler.schedule(() -> remind(runId),
                properties.dispatchReminderMs(), TimeUnit.MILLISECONDS));
        armed.add(scheduler.schedule(() -> timeout(runId),
                properties.dispatchTimeoutMs(), TimeUnit.MILLISECONDS));
        timers.put(runId, armed);
    }

    private void remind(UUID runId) {
        Dispatch dispatch = byRun.get(runId);
        if (dispatch == null || dispatch.session().closed() || !isActive(runId)
                || !dispatch.markReminderSent()) {
            return;
        }
        String who = dispatch.agentName.isBlank() ? "" : dispatch.agentName + " ";
        frames.toBridge(dispatch.session(),
                LiveFrames.tower("[tower] " + who + "仲做緊", "idle"));
        log.debug("live dispatch reminder: run={}", runId);
    }

    private void timeout(UUID runId) {
        Dispatch dispatch = byRun.get(runId);
        if (dispatch == null || dispatch.session().closed()) {
            remove(runId);
            return;
        }
        if (!isActive(runId)) {
            // Terminal already: the completion listener owns its frames.
            remove(runId);
            return;
        }
        // Remove first so the stop's completion event cannot report a second outcome.
        remove(runId);
        try {
            runs.cancelRun(runId);
        } catch (RuntimeException e) {
            log.debug("live dispatch timeout could not stop the run: run={} reason={}",
                    runId, e.getClass().getSimpleName());
        }
        String state = "failed: timeout";
        frames.toPhone(dispatch.session(),
                LiveFrames.runEvent(runId, "failed", state, "dispatch"));
        String who = dispatch.agentName.isBlank() ? "" : dispatch.agentName + " ";
        frames.toBridge(dispatch.session(),
                LiveFrames.tower("[tower] " + who + "失敗：timeout", "interrupt"));
        log.debug("live dispatch timed out: run={}", runId);
    }

    private boolean isActive(UUID runId) {
        if (runs == null) {
            return false;
        }
        try {
            RunResponse run = runs.getRun(runId);
            RunStatus status = run == null ? null : run.getStatus();
            return status != null && status != RunStatus.COMPLETED && status != RunStatus.FAILED
                    && status != RunStatus.CANCELLED && status != RunStatus.ABORTED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void cancelTimers(UUID runId) {
        List<ScheduledFuture<?>> armed = timers.remove(runId);
        if (armed == null) {
            return;
        }
        for (ScheduledFuture<?> future : armed) {
            future.cancel(false);
        }
    }

    @PreDestroy
    void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    public Optional<Dispatch> find(UUID runId) {
        return runId == null ? Optional.empty() : Optional.ofNullable(byRun.get(runId));
    }

    public Optional<Dispatch> remove(UUID runId) {
        if (runId == null) {
            return Optional.empty();
        }
        cancelTimers(runId);
        return Optional.ofNullable(byRun.remove(runId));
    }

    /** Every child run of this socket is forgotten: the socket can receive nothing more. */
    public void removeSocket(LiveSession session) {
        byRun.values().removeIf(dispatch -> dispatch.session() == session);
    }

    public Set<UUID> runIds() {
        return Set.copyOf(byRun.keySet());
    }
}
