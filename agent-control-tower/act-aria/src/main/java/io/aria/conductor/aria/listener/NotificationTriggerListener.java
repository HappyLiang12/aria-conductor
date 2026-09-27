package io.aria.conductor.aria.listener;

import io.aria.conductor.aria.service.NotificationService;
import io.aria.conductor.common.event.*;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class NotificationTriggerListener {

    private final NotificationService notificationService;

    public NotificationTriggerListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @EventListener
    public void onRunCompleted(RunCompletedEvent event) {
        String title = switch (event.getStatus()) {
            case COMPLETED -> "Run completed successfully";
            case FAILED -> "Run failed";
            default -> "Run finished with status: " + event.getStatus();
        };
        notificationService.create("run." + event.getStatus().name().toLowerCase().replace("_", "."),
                title,
                "Run " + event.getRunId() + " finished with status " + event.getStatus() + ".",
                "RUN", event.getRunId().toString());
    }

    @EventListener
    public void onApprovalRequested(ApprovalRequestedEvent event) {
        notificationService.create("approval.requested",
                "Approval requested",
                "Approval " + event.getApprovalId() + " is waiting for your decision.",
                "APPROVAL", event.getApprovalId().toString());
    }

    /**
     * UX-6: an expired approval persists an unread notification with resource APPROVAL, so the
     * bell and the notification feed close the loop the "approval requested" entry opened
     * instead of staying silent while the ask vanishes from the queue.
     */
    @EventListener
    public void onApprovalExpired(ApprovalExpiredEvent event) {
        notificationService.create("approval.expired",
                "Approval expired",
                "Approval " + event.getApprovalId() + " expired without a decision ("
                        + (event.getReason() != null ? event.getReason() : "no reason recorded") + ").",
                "APPROVAL", event.getApprovalId().toString());
    }

    @EventListener
    public void onKnowledgeSubmitted(KnowledgeSubmittedEvent event) {
        notificationService.create("knowledge.submitted",
                "Knowledge submitted: " + event.getName(),
                "Knowledge item '" + event.getName() + "' of type " + event.getType() + " has been submitted.",
                "KNOWLEDGE", event.getItemId().toString());
    }

    @EventListener
    public void onReportGenerated(ReportGeneratedEvent event) {
        notificationService.create("report.generated",
                "Report generated: " + event.getTitle(),
                "Report '" + event.getTitle() + "' has been generated.",
                "REPORT", event.getReportId());
    }
}
