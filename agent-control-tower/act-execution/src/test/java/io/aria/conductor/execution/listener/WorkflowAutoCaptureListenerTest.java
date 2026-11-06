package io.aria.conductor.execution.listener;

import io.aria.conductor.common.event.WorkflowAdvancedEvent;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.common.port.KnowledgeCapturePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowAutoCaptureListenerTest {

    @Mock
    KnowledgeCapturePort knowledgeCapturePort;

    private WorkflowAutoCaptureListener listener;

    @BeforeEach
    void setUp() {
        listener = new WorkflowAutoCaptureListener(knowledgeCapturePort);
    }

    @Test
    void completedWorkflow_shouldSubmitKnowledgeItem() {
        UUID chainId = UUID.randomUUID();
        UUID knowledgeItemId = UUID.randomUUID();

        WorkflowAdvancedEvent event = new WorkflowAdvancedEvent(
                this, chainId, "Test Workflow", 2, 2, WorkflowChain.Status.COMPLETED);

        when(knowledgeCapturePort.captureWorkflowChain(chainId)).thenReturn(knowledgeItemId);

        listener.onWorkflowCompleted(event);

        verify(knowledgeCapturePort).captureWorkflowChain(chainId);
    }

    @Test
    void singleStepWorkflow_shouldStillCallPort() {
        // The listener delegates skip decisions to the port; it always calls captureWorkflowChain
        UUID chainId = UUID.randomUUID();

        WorkflowAdvancedEvent event = new WorkflowAdvancedEvent(
                this, chainId, "Single Step", 0, 0, WorkflowChain.Status.COMPLETED);

        when(knowledgeCapturePort.captureWorkflowChain(chainId)).thenReturn(null);

        listener.onWorkflowCompleted(event);

        verify(knowledgeCapturePort).captureWorkflowChain(chainId);
    }

    @Test
    void alreadyCapturedWorkflow_portReturnsNull() {
        UUID chainId = UUID.randomUUID();

        WorkflowAdvancedEvent event = new WorkflowAdvancedEvent(
                this, chainId, "Already Captured", 1, 1, WorkflowChain.Status.COMPLETED);

        when(knowledgeCapturePort.captureWorkflowChain(chainId)).thenReturn(null);

        listener.onWorkflowCompleted(event);

        verify(knowledgeCapturePort).captureWorkflowChain(chainId);
    }

    @Test
    void nonCompletedEvent_shouldBeNoOp() {
        UUID chainId = UUID.randomUUID();

        // RUNNING event should be ignored
        WorkflowAdvancedEvent event = new WorkflowAdvancedEvent(
                this, chainId, "Running WF", 0, 1, WorkflowChain.Status.RUNNING);

        listener.onWorkflowCompleted(event);

        verify(knowledgeCapturePort, never()).captureWorkflowChain(any());
    }

    @Test
    void failedEvent_shouldBeNoOp() {
        UUID chainId = UUID.randomUUID();

        WorkflowAdvancedEvent event = new WorkflowAdvancedEvent(
                this, chainId, "Failed WF", 0, 0, WorkflowChain.Status.FAILED);

        listener.onWorkflowCompleted(event);

        verify(knowledgeCapturePort, never()).captureWorkflowChain(any());
    }

    @Test
    void portThrowsException_shouldNotPropagate() {
        UUID chainId = UUID.randomUUID();

        WorkflowAdvancedEvent event = new WorkflowAdvancedEvent(
                this, chainId, "Error WF", 1, 1, WorkflowChain.Status.COMPLETED);

        when(knowledgeCapturePort.captureWorkflowChain(chainId))
                .thenThrow(new RuntimeException("DB connection lost"));

        // Should not throw
        listener.onWorkflowCompleted(event);

        verify(knowledgeCapturePort).captureWorkflowChain(chainId);
    }
}
