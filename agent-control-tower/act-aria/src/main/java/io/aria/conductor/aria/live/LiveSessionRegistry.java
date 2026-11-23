package io.aria.conductor.aria.live;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The live sockets this process owns, keyed by {@link org.springframework.web.socket.WebSocketSession#getId()}
 * (spec §"Architecture").
 *
 * <p>The handler registers a session the moment the upgrade completes and removes it the
 * moment the socket closes, so a lookup can never resurrect a closed socket. Task 4 reads
 * this map to find the socket a live run's approval belongs to; nothing is parked here
 * yet.
 */
@Component
public class LiveSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(LiveSessionRegistry.class);

    private final Map<String, LiveSession> sessions = new ConcurrentHashMap<>();

    void register(LiveSession session) {
        sessions.put(session.ws().getId(), session);
    }

    /** @return the session for this socket id, or null once it has closed */
    public LiveSession get(String wsSessionId) {
        return sessions.get(wsSessionId);
    }

    /** Removes and returns the session; the caller still owns closing its bridge. */
    LiveSession remove(String wsSessionId) {
        return sessions.remove(wsSessionId);
    }

    public Collection<LiveSession> all() {
        return sessions.values();
    }

    public int size() {
        return sessions.size();
    }

    /** A backend stop must not leave children behind (spec §Deployment). */
    @PreDestroy
    void shutdown() {
        for (LiveSession session : sessions.values()) {
            LiveBridge bridge = session.bridge();
            if (bridge != null) {
                try {
                    bridge.close();
                } catch (RuntimeException e) {
                    log.debug("aria live bridge close failed during shutdown");
                }
            }
        }
        sessions.clear();
    }
}
