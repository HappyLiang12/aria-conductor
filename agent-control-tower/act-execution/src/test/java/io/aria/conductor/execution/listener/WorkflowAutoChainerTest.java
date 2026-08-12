package io.aria.conductor.execution.listener;

import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.event.WorkflowAdvancedEvent;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.execution.dod.DoDService;
import io.aria.conductor.test.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link WorkflowAutoChainer}: on run completion the listener must
 * advance the owning workflow chain (publishing a RUNNING advancement event),
 * complete it on the last step (nextStep=-1, COMPLETED), mark the step failed
 * for FAILED/CANCELLED runs, and stay a strict no-op for runs outside any chain.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowAutoChainerTest {

    @Mock private WorkflowService workflowService;
    @Mock private DoDService dodService;
    @Mock private WorkflowChainRepository chainRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private WorkflowAutoChainer chainer;

    private final UUID runId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();
    private WorkflowChain chain;

    @BeforeEach
    void setUp() {
        chainer = new WorkflowAutoChainer(workflowService, dodService, chainRepository, eventPublisher);
        chain = TestDataBuilder.aWorkflowChain()
                .withName("release-train")
                .withStatus(WorkflowChain.Status.RUNNING)
                .build();
    }

    @Test
    void onRunCompleted_runNotInAnyChain_isStrictNoOp() {
        when(workflowService.findChainByRunId(runId)).thenReturn(null);

        chainer.onRunCompleted(completed(RunStatus.COMPLETED, "out"));

        // The chain lookup used the completed run's id; nothing else happened.
        ArgumentCaptor<UUID> lookupCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(workflowService).findChainByRunId(lookupCaptor.capture());
        assertThat(lookupCaptor.getValue()).isEqualTo(runId);
        verify(workflowService, never()).advanceWorkflow(any(), anyInt(), any());
        verify(workflowService, never()).markStepFailed(any(), anyInt(), anyString());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void onRunCompleted_stepIndexNotFound_doesNotAdvanceOrFail() {
        when(workflowService.findChainByRunId(runId)).thenReturn(chain);
        when(workflowService.findStepIndex(chain, runId)).thenReturn(-1);

        chainer.onRunCompleted(completed(RunStatus.COMPLETED, "out"));

        verify(workflowService, never()).advanceWorkflow(any(), anyInt(), any());
        verify(workflowService, never()).markStepFailed(any(), anyInt(), anyString());
        verify(eventPublisher, never()).publishEvent(any());
        // Illegal-state guard: the chain object itself must remain untouched.
        assertThat(chain.getStatus()).isEqualTo(WorkflowChain.Status.RUNNING);
    }

    @Test
    void onRunCompleted_failedRun_marksStepFailedWithOutputAppended() {
        when(workflowService.findChainByRunId(runId)).thenReturn(chain);
        when(workflowService.findStepIndex(chain, runId)).thenReturn(2);

        chainer.onRunCompleted(completed(RunStatus.FAILED, "LLM quota exceeded"));

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(workflowService).markStepFailed(eq(chain.getId()), eq(2), messageCaptor.capture());
        assertThat(messageCaptor.getValue()).isEqualTo("Run failed: LLM quota exceeded");
        verify(workflowService, never()).advanceWorkflow(any(), anyInt(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void onRunCompleted_failedRunWithoutOutput_marksStepFailedWithPlainMessage() {
        when(workflowService.findChainByRunId(runId)).thenReturn(chain);
        when(workflowService.findStepIndex(chain, runId)).thenReturn(0);

        chainer.onRunCompleted(completed(RunStatus.FAILED, null));

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(workflowService).markStepFailed(eq(chain.getId()), eq(0), messageCaptor.capture());
        assertThat(messageCaptor.getValue()).isEqualTo("Run failed");
    }

    @Test
    void onRunCompleted_cancelledRun_marksStepFailedAsCancelled() {
        when(workflowService.findChainByRunId(runId)).thenReturn(chain);
        when(workflowService.findStepIndex(chain, runId)).thenReturn(1);

        chainer.onRunCompleted(completed(RunStatus.CANCELLED, "ignored output"));

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(workflowService).markStepFailed(eq(chain.getId()), eq(1), messageCaptor.capture());
        assertThat(messageCaptor.getValue()).isEqualTo("Run was cancelled");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void onRunCompleted_abortedRun_marksStepFailedAsAborted() {
        when(workflowService.findChainByRunId(runId)).thenReturn(chain);
        when(workflowService.findStepIndex(chain, runId)).thenReturn(1);

        chainer.onRunCompleted(completed(RunStatus.ABORTED, "ignored output"));

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(workflowService).markStepFailed(eq(chain.getId()), eq(1), messageCaptor.capture());
        assertThat(messageCaptor.getValue()).isEqualTo("Run was aborted");
        verify(workflowService, never()).advanceWorkflow(any(), anyInt(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void onRunCompleted_successfulRun_advancesChainAndPublishesRunningEvent() {
        when(workflowService.findChainByRunId(runId)).thenReturn(chain);
        when(workflowService.findStepIndex(chain, runId)).thenReturn(1);
        when(workflowService.advanceWorkflow(chain.getId(), 1, "step-1 output")).thenReturn(true);

        chainer.onRunCompleted(completed(RunStatus.COMPLETED, "step-1 output"));

        ArgumentCaptor<WorkflowAdvancedEvent> eventCaptor =
                ArgumentCaptor.forClass(WorkflowAdvancedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        WorkflowAdvancedEvent event = eventCaptor.getValue();
        assertThat(event.getWorkflowId()).isEqualTo(chain.getId());
        assertThat(event.getWorkflowName()).isEqualTo("release-train");
        assertThat(event.getCompletedStep()).isEqualTo(1);
        assertThat(event.getNextStep()).isEqualTo(2);
        assertThat(event.getChainStatus()).isEqualTo(WorkflowChain.Status.RUNNING);
    }

    @Test
    void onRunCompleted_lastStepDone_publishesCompletedEventWithNoNextStep() {
        when(workflowService.findChainByRunId(runId)).thenReturn(chain);
        when(workflowService.findStepIndex(chain, runId)).thenReturn(3);
        when(workflowService.advanceWorkflow(chain.getId(), 3, "final output")).thenReturn(false);

        chainer.onRunCompleted(completed(RunStatus.COMPLETED, "final output"));

        ArgumentCaptor<WorkflowAdvancedEvent> eventCaptor =
                ArgumentCaptor.forClass(WorkflowAdvancedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        WorkflowAdvancedEvent event = eventCaptor.getValue();
        assertThat(event.getCompletedStep()).isEqualTo(3);
        assertThat(event.getNextStep()).isEqualTo(-1);
        assertThat(event.getChainStatus()).isEqualTo(WorkflowChain.Status.COMPLETED);
    }

    @Test
    void onRunCompleted_serviceFailure_isSwallowedWithoutPublishing() {
        when(workflowService.findChainByRunId(runId))
                .thenThrow(new IllegalStateException("chain store unavailable"));

        assertThatCode(() -> chainer.onRunCompleted(completed(RunStatus.COMPLETED, "out")))
                .doesNotThrowAnyException();
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ---- helpers ----

    private RunCompletedEvent completed(RunStatus status, String finalOutput) {
        return new RunCompletedEvent(this, runId, agentId, status, finalOutput);
    }
}
