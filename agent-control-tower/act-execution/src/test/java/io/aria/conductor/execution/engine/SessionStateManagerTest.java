package io.aria.conductor.execution.engine;

import io.aria.conductor.common.model.AgentSession;
import io.aria.conductor.common.model.SessionStatus;
import io.aria.conductor.execution.repository.AgentSessionRepository;
import io.aria.conductor.test.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Behaviour tests for {@link SessionStateManager} — the in-memory session cache
 * with final-state persistence. Covers load-or-create precedence (cache → DB →
 * new), clear semantics, and the no-op guards that protect against operating on
 * an un-cached run.
 */
@ExtendWith(MockitoExtension.class)
class SessionStateManagerTest {

    @Mock
    private AgentSessionRepository sessionRepository;

    private SessionStateManager manager;
    private final UUID runId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        manager = new SessionStateManager(sessionRepository);
    }

    // --- loadOrCreateSession ---

    @Test
    void loadOrCreate_createsActiveSession_whenAbsentFromCacheAndDb() {
        when(sessionRepository.findById(runId)).thenReturn(Optional.empty());

        AgentSession session = manager.loadOrCreateSession(runId, agentId);

        assertThat(session.getRunId()).isEqualTo(runId);
        assertThat(session.getAgentId()).isEqualTo(agentId);
        assertThat(session.getStatus()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(session.getTurnCount()).isZero();
        // New sessions are cached but not persisted here (persistence is deferred).
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void loadOrCreate_returnsCachedInstance_withoutHittingDbTwice() {
        when(sessionRepository.findById(runId)).thenReturn(Optional.empty());

        AgentSession first = manager.loadOrCreateSession(runId, agentId);
        AgentSession second = manager.loadOrCreateSession(runId, agentId);

        assertThat(second).isSameAs(first);
        verify(sessionRepository, times(1)).findById(runId); // second call served from cache
    }

    @Test
    void loadOrCreate_loadsFromDbAndCaches_whenPersistedSessionExists() {
        AgentSession persisted = TestDataBuilder.anAgentSession()
                .withRunId(runId).withAgentId(agentId).withTurnCount(7).build();
        when(sessionRepository.findById(runId)).thenReturn(Optional.of(persisted));

        AgentSession loaded = manager.loadOrCreateSession(runId, agentId);
        AgentSession again = manager.loadOrCreateSession(runId, agentId);

        assertThat(loaded).isSameAs(persisted);
        assertThat(loaded.getTurnCount()).isEqualTo(7);
        assertThat(again).isSameAs(persisted);
        verify(sessionRepository, times(1)).findById(runId); // only the first miss hits the DB
    }

    // --- clearSession ---

    @Test
    void clearSession_persistsFinalStateAndEvictsFromCache() {
        when(sessionRepository.findById(runId)).thenReturn(Optional.empty());
        manager.loadOrCreateSession(runId, agentId);

        manager.clearSession(runId);

        verify(sessionRepository).save(any(AgentSession.class));
        assertThat(manager.getSession(runId)).isNull();
    }

    @Test
    void clearSession_isNoOp_whenSessionNotCached() {
        manager.clearSession(runId);
        verifyNoInteractions(sessionRepository);
    }

    // --- updateSessionStatus / getSession ---

    @Test
    void updateSessionStatus_mutatesCachedSession() {
        when(sessionRepository.findById(runId)).thenReturn(Optional.empty());
        AgentSession session = manager.loadOrCreateSession(runId, agentId);

        manager.updateSessionStatus(runId, SessionStatus.COMPLETED);

        assertThat(session.getStatus()).isEqualTo(SessionStatus.COMPLETED);
    }

    @Test
    void updateSessionStatus_isNoOp_whenSessionNotCached() {
        manager.updateSessionStatus(runId, SessionStatus.COMPLETED);
        assertThat(manager.getSession(runId)).isNull();
    }

    @Test
    void getSession_returnsNull_forUnknownRun() {
        assertThat(manager.getSession(UUID.randomUUID())).isNull();
    }
}
