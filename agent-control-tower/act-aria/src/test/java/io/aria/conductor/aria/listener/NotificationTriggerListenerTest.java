package io.aria.conductor.aria.listener;

import io.aria.conductor.aria.service.NotificationService;
import io.aria.conductor.common.event.*;
import io.aria.conductor.common.model.RunStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationTriggerListenerTest {

    @Mock NotificationService notificationService;
    @InjectMocks NotificationTriggerListener listener;

    @Test
    void onRunCompleted_withCompletedStatus() {
        UUID runId = UUID.randomUUID();
        listener.onRunCompleted(new RunCompletedEvent(this, runId, UUID.randomUUID(), RunStatus.COMPLETED));
        verify(notificationService).create("run.completed", "Run completed successfully",
                "Run " + runId + " finished with status COMPLETED.", "RUN", runId.toString());
    }

    @Test
    void onRunCompleted_withFailedStatus() {
        UUID runId = UUID.randomUUID();
        listener.onRunCompleted(new RunCompletedEvent(this, runId, UUID.randomUUID(), RunStatus.FAILED));
        verify(notificationService).create("run.failed", "Run failed",
                "Run " + runId + " finished with status FAILED.", "RUN", runId.toString());
    }

    @Test
    void onApprovalRequested() {
        UUID approvalId = UUID.randomUUID();
        listener.onApprovalRequested(new ApprovalRequestedEvent(this, approvalId, UUID.randomUUID(), UUID.randomUUID()));
        verify(notificationService).create("approval.requested", "Approval requested",
                "Approval " + approvalId + " is waiting for your decision.", "APPROVAL", approvalId.toString());
    }

    @Test
    void onKnowledgeSubmitted() {
        UUID itemId = UUID.randomUUID();
        listener.onKnowledgeSubmitted(new KnowledgeSubmittedEvent(this, itemId, "SKILL", "TestSkill"));
        verify(notificationService).create("knowledge.submitted", "Knowledge submitted: TestSkill",
                "Knowledge item 'TestSkill' of type SKILL has been submitted.", "KNOWLEDGE", itemId.toString());
    }

    @Test
    void onReportGenerated() {
        listener.onReportGenerated(new ReportGeneratedEvent(this, "rpt-1", "Q2 Report", "user1"));
        verify(notificationService).create("report.generated", "Report generated: Q2 Report",
                "Report 'Q2 Report' has been generated.", "REPORT", "rpt-1");
    }
}
