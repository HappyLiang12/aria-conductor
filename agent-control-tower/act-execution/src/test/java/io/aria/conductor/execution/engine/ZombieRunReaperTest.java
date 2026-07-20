package io.aria.conductor.execution.engine;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Regression tests for the zombie-run reaper:
 * <ul>
 *   <li>Reaps a stale RUNNING run with no active context and publishes RunCompletedEvent(FAILED).</li>
 *   <li>Does NOT reap (lost-update guard) when the run transitioned to a terminal state
 *       between the initial query and the save.</li>
 *   <li>Does NOT reap a run that still has an active execution context.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ZombieRunReaperTest {

    @Mock
    private RunRepository runRepository;

    @Mock
    private AgentLoopEngine agentLoopEngine;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private ZombieRunReaper reaper;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(reaper, "timeoutMinutes", 120L);
    }

    private Run runningRun(Instant lastActivity) {
        Run run = new Run();
        run.setId(UUID.randomUUID());
        run.setAgentId(UUID.randomUUID());
        run.setStatus(RunStatus.RUNNING);
        run.setCreatedAt(lastActivity);
        run.setUpdatedAt(lastActivity);
        return run;
    }

    @Test
    void staleRunWithoutActiveContext_shouldBeReapedAndPublishEvent() {
        Run run = runningRun(Instant.now().minus(3, ChronoUnit.HOURS));
        when(runRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of(run));
        when(agentLoopEngine.hasActiveContext(run.getId())).thenReturn(false);
        when(runRepository.findById(run.getId())).thenReturn(Optional.of(run));

        reaper.reapZombieRuns();

        ArgumentCaptor<Run> saved = ArgumentCaptor.forClass(Run.class);
        verify(runRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(saved.getValue().getErrorMessage()).contains("Zombie run reaped");
        assertThat(saved.getValue().getCompletedAt()).isNotNull();

        ArgumentCaptor<RunCompletedEvent> event = ArgumentCaptor.forClass(RunCompletedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(event.getValue().getRunId()).isEqualTo(run.getId());
    }

    @Test
    void runCancelledBetweenQueryAndSave_shouldNotBeReaped() {
        // Initial query sees RUNNING, but by the time we re-read it an external actor
        // (RunService.cancelRun) has already moved it to CANCELLED.
        Run staleView = runningRun(Instant.now().minus(3, ChronoUnit.HOURS));
        Run currentView = new Run();
        currentView.setId(staleView.getId());
        currentView.setAgentId(staleView.getAgentId());
        currentView.setStatus(RunStatus.CANCELLED);

        when(runRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of(staleView));
        when(agentLoopEngine.hasActiveContext(staleView.getId())).thenReturn(false);
        when(runRepository.findById(staleView.getId())).thenReturn(Optional.of(currentView));

        reaper.reapZombieRuns();

        verify(runRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void runWithActiveContext_shouldNotBeReaped() {
        Run run = runningRun(Instant.now().minus(3, ChronoUnit.HOURS));
        when(runRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of(run));
        when(agentLoopEngine.hasActiveContext(run.getId())).thenReturn(true);

        reaper.reapZombieRuns();

        verify(runRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void recentRun_shouldNotBeReaped() {
        Run run = runningRun(Instant.now().minus(5, ChronoUnit.MINUTES));
        when(runRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of(run));

        reaper.reapZombieRuns();

        verify(agentLoopEngine, never()).hasActiveContext(any());
        verify(runRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }
}
