package io.aria.conductor.execution.engine;

import io.aria.conductor.common.model.AgentSession;
import io.aria.conductor.common.model.SessionStatus;
import io.aria.conductor.execution.llm.LlmResponse;
import io.aria.conductor.execution.pipeline.ActionResult;
import io.aria.conductor.execution.repository.AgentSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages agent sessions in-memory with periodic persistence to H2.
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
     * Update session with LLM response and action results.
     */
    public void updateSession(UUID runId, LlmResponse response, List<ActionResult> actionResults) {
        AgentSession session = sessionCache.get(runId);
        if (session == null) {
            log.warn("Session not found for update: runId={}", runId);
            return;
        }

        // Update token counts
        session.setTotalInputTokens(session.getTotalInputTokens() + response.inputTokens());
        session.setTotalOutputTokens(session.getTotalOutputTokens() + response.outputTokens());

        // Increment turn count
        session.setTurnCount(session.getTurnCount() + 1);

        // Update memory with latest response (append)
        String memoryUpdate = buildMemoryUpdate(response, actionResults);
        if (session.getMemory() == null || session.getMemory().isEmpty()) {
            session.setMemory(memoryUpdate);
        } else {
            session.setMemory(session.getMemory() + "\n" + memoryUpdate);
        }

        log.debug("Session updated: runId={}, turnCount={}, totalInputTokens={}, totalOutputTokens={}",
                runId, session.getTurnCount(), session.getTotalInputTokens(), session.getTotalOutputTokens());
    }

    /**
     * Persist session to H2. Called every 5 iterations.
     */
    @Transactional
    public void persistSession(UUID runId) {
        AgentSession session = sessionCache.get(runId);
        if (session == null) {
            log.warn("Session not found for persistence: runId={}", runId);
            return;
        }

        log.info("Persisting session: runId={}, turnCount={}", runId, session.getTurnCount());
        // Re-cache the persisted instance so its @Version stays in sync with the DB; repeatedly
        // save()-ing a detached entity with a stale version causes OptimisticLockException.
        sessionCache.put(runId, sessionRepository.save(session));
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

    private String buildMemoryUpdate(LlmResponse response, List<ActionResult> actionResults) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Turn] content=").append(truncate(response.content(), 200));
        if (response.hasToolCalls()) {
            sb.append(", toolCalls=").append(response.toolCalls().size());
        }
        if (actionResults != null && !actionResults.isEmpty()) {
            sb.append(", results=").append(actionResults.size());
        }
        return sb.toString();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}