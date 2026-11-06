package io.aria.conductor.aria.listener;

import io.aria.conductor.aria.service.NotificationService;
import io.aria.conductor.common.event.ApprovalDecidedEvent;
import io.aria.conductor.common.event.ApprovalExpiredEvent;
import io.aria.conductor.common.model.ApprovalStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

class NotificationTriggerListenerApprovalSettleTest {

    private NotificationService notifications;
    private NotificationTriggerListener listener;

    @BeforeEach
    void setUp() {
        notifications = Mockito.mock(NotificationService.class);
        listener = new NotificationTriggerListener(notifications);
    }

    @Test
    void decidedApprovalFlipsItsRequestedNotification() {
        UUID approvalId = UUID.randomUUID();
        listener.onApprovalDecided(new ApprovalDecidedEvent(this, approvalId, ApprovalStatus.APPROVED));
        verify(notifications).markRequestedReadForApproval(approvalId.toString());
    }

    @Test
    void expiredApprovalFlipsTheRequestAndNamesTheTool() {
        UUID approvalId = UUID.randomUUID();
        listener.onApprovalExpired(new ApprovalExpiredEvent(this, approvalId, UUID.randomUUID(),
                "run ended", "run_agent"));
        verify(notifications).markRequestedReadForApproval(approvalId.toString());
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notifications).create(eq("approval.expired"), eq("Approval expired"),
                body.capture(), eq("APPROVAL"), eq(approvalId.toString()));
        assertThat(body.getValue()).contains("run_agent");
    }
}
