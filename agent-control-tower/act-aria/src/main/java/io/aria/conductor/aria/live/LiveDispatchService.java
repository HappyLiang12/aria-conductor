package io.aria.conductor.aria.live;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    public void register(UUID runId, LiveSession session, String agentName) {
        if (runId == null || session == null) {
            return;
        }
        byRun.put(runId, new Dispatch(runId, session, agentName == null ? "" : agentName, Instant.now()));
        log.debug("live dispatch registered: run={}", runId);
    }

    public Optional<Dispatch> find(UUID runId) {
        return runId == null ? Optional.empty() : Optional.ofNullable(byRun.get(runId));
    }

    public Optional<Dispatch> remove(UUID runId) {
        return runId == null ? Optional.empty() : Optional.ofNullable(byRun.remove(runId));
    }

    /** Every child run of this socket is forgotten: the socket can receive nothing more. */
    public void removeSocket(LiveSession session) {
        byRun.values().removeIf(dispatch -> dispatch.session() == session);
    }

    public Set<UUID> runIds() {
        return Set.copyOf(byRun.keySet());
    }
}
