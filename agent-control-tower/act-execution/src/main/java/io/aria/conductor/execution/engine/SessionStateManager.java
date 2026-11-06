package io.aria.conductor.execution.engine;

import io.aria.conductor.common.model.AgentSession;
import io.aria.conductor.common.model.SessionStatus;
import io.aria.conductor.execution.repository.AgentSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages agent sessions in memory and persists the final state to H2.
 */
@Slf4j
@Component
public class SessionStateManager {

    private final AgentSessionRepository sessionRepository;
    private final Map<UUID, AgentSession> sessionCache = new ConcurrentHashMap<>();

    public SessionStateManager(AgentSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /**
     * Load existing session from cache or DB, or create a new one.
     */
    public AgentSession loadOrCreateSession(UUID runId, UUID agentId) {
        // Check in-memory cache first
        AgentSession cached = sessionCache.get(runId);
        if (cached != null) {
            log.debug("Session loaded from cache: runId={}", runId);
            return cached;
        }

        // Check DB
        AgentSession session = sessionRepository.findById(runId).orElse(null);
        if (session != null) {
            log.debug("Session loaded from DB: runId={}", runId);
            sessionCache.put(runId, session);
            return session;
        }

        // Create new session
        log.info("Creating new session: runId={}, agentId={}", runId, agentId);
        AgentSession newSession = AgentSession.builder()
                .runId(runId)
                .agentId(agentId)
                .status(SessionStatus.ACTIVE)
                .turnCount(0)
                .totalInputTokens(0)
                .totalOutputTokens(0)
                .build();

        sessionCache.put(runId, newSession);
        return newSession;
    }


    /**
     * Clear session from cache and persist final state.
     */
    @Transactional
    public void clearSession(UUID runId) {
        AgentSession session = sessionCache.remove(runId);
        if (session != null) {
            log.info("Clearing session: runId={}, final turnCount={}", runId, session.getTurnCount());
            sessionRepository.save(session);
        }
    }

    /**
     * Update session status.
     */
    public void updateSessionStatus(UUID runId, SessionStatus status) {
        AgentSession session = sessionCache.get(runId);
        if (session != null) {
            session.setStatus(status);
            log.debug("Session status updated: runId={}, status={}", runId, status);
        }
    }

    /**
     * Get session snapshot for status queries.
     */
    public AgentSession getSession(UUID runId) {
        return sessionCache.get(runId);
    }
}