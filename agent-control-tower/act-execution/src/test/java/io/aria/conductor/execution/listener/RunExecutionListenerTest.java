package io.aria.conductor.execution.listener;

import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.event.RunStartedEvent;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.engine.AgentLoopEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RunExecutionListenerTest {

    @Mock
    private AgentLoopEngine agentLoopEngine;

    @InjectMocks
    private RunExecutionListener listener;

    @Test
    void onRunStarted_shouldCallStartRun() {
        UUID runId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        RunStartedEvent event = new RunStartedEvent(this, runId, agentId);

        listener.onRunStarted(event);

        verify(agentLoopEngine).startRun(runId);
    }

    @Test
    void onRunStarted_shouldNotPropagateException() {
        UUID runId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        RunStartedEvent event = new RunStartedEvent(this, runId, agentId);
        doThrow(new RuntimeException("engine failure")).when(agentLoopEngine).startRun(runId);

        // Should not throw
        listener.onRunStarted(event);

        verify(agentLoopEngine).startRun(runId);
    }

    /**
     * The listener carries the recorded CANCELLED transition into the engine's
     * session bookkeeping only: the verified stop of a live run-owned core
     * happens in RunService before CANCELLED is persisted, not here.
     */
    @Test
    void onRunCompleted_cancelled_stillCallsTheEngineForBookkeeping() {
        UUID runId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        RunCompletedEvent event = new RunCompletedEvent(this, runId, agentId, RunStatus.CANCELLED);

        listener.onRunCompleted(event);

        verify(agentLoopEngine).cancelRun(runId);
        verifyNoMoreInteractions(agentLoopEngine);
    }
}
