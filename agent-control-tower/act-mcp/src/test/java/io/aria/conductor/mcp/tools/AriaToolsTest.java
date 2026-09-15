package io.aria.conductor.mcp.tools;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.aria.dto.NotificationCountDto;
import io.aria.conductor.aria.dto.NotificationDto;
import io.aria.conductor.aria.dto.ScheduledJobDto;
import io.aria.conductor.aria.service.NotificationService;
import io.aria.conductor.aria.service.ScheduledJobService;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.SessionTrajectory;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AriaToolsTest {

    private static final Instant NOW = Instant.parse("2024-05-01T10:00:00Z");

    @Mock NotificationService notificationService;
    @Mock ScheduledJobService scheduledJobService;
    @Mock RunRepository runRepository;
    @Mock SessionTrajectoryRepository trajectoryRepository;
    McpProperties mcpProperties;
    AriaTools tools;

    @BeforeEach
    void setUp() {
        mcpProperties = new McpProperties();
        tools = new AriaTools(notificationService, scheduledJobService, runRepository,
                trajectoryRepository, mcpProperties);
    }

    private static NotificationDto notification(String id, String title, boolean read) {
        return new NotificationDto(id, "JOB", title, "body", "JOB", "j1", "j1", read, NOW);
    }

    private static ScheduledJobDto job(String id) {
        return new ScheduledJobDto(id, "RECURRING", "REMINDER", "Daily brief", "0 9 * * *",
                "Brief", null, null, "body", "ACTIVE", NOW, NOW);
    }

    @Test
    void listNotifications_delegatesWithDefaultPaging() {
        when(notificationService.list(0, 20))
                .thenReturn(new PageImpl<>(List.of(notification("n1", "Job fired", false))));

        String json = tools.listNotifications(null, null);

        assertThat(json).contains("\"ok\":true").contains("Job fired").contains("\"totalElements\":1");
    }

    @Test
    void listNotifications_passesExplicitPaging() {
        when(notificationService.list(2, 5)).thenReturn(new PageImpl<>(List.of()));

        String json = tools.listNotifications(2, 5);

        verify(notificationService).list(2, 5);
        assertThat(json).contains("\"ok\":true").contains("\"content\":[]");
    }

    @Test
    void getUnreadNotificationCount_returnsCount() {
        when(notificationService.getUnreadCount()).thenReturn(new NotificationCountDto(3));

        String json = tools.getUnreadNotificationCount();

        assertThat(json).contains("\"ok\":true").contains("\"unreadCount\":3");
    }

    @Test
    void markNotificationRead_marksAndWraps() {
        when(notificationService.markRead("n1")).thenReturn(notification("n1", "Job fired", true));

        String json = tools.markNotificationRead("n1");

        assertThat(json).contains("\"ok\":true").contains("n1").contains("Job fired");
    }

    @Test
    void markNotificationRead_mapsFailureWithoutStack_whenDebugOff() {
        when(notificationService.markRead("missing"))
                .thenThrow(new RuntimeException("Notification not found: missing"));

        String json = tools.markNotificationRead("missing");

        assertThat(json).contains("\"errorType\":\"NOTIFICATION_READ_FAILED\"");
        assertThat(json).contains("Notification not found: missing");
        assertThat(json).doesNotContain("stackTrace");
    }

    @Test
    void markAllNotificationsRead_returnsAck() {
        String json = tools.markAllNotificationsRead();

        verify(notificationService).markAllRead();
        assertThat(json).contains("\"ok\":true").contains("markedAllRead");
    }

    @Test
    void listScheduledJobs_delegatesWithFilters() {
        when(scheduledJobService.list("REMINDER", "ACTIVE")).thenReturn(List.of(job("j1")));

        String json = tools.listScheduledJobs("REMINDER", "ACTIVE");

        assertThat(json).contains("\"ok\":true").contains("Daily brief").contains("j1");
    }

    @Test
    void listScheduledJobs_blankFiltersListAll() {
        when(scheduledJobService.list(null, null)).thenReturn(List.of());

        String json = tools.listScheduledJobs(" ", "");

        verify(scheduledJobService).list(null, null);
        assertThat(json).contains("\"ok\":true");
    }

    @Test
    void createScheduledJob_normalizesEnumsAndDelegates() {
        when(scheduledJobService.create(any())).thenReturn(job("j9"));
        ArgumentCaptor<ScheduledJobDto> captor = ArgumentCaptor.forClass(ScheduledJobDto.class);

        String json = tools.createScheduledJob("recurring", "monitor", "Daily brief",
                "0 9 * * *", "Brief", "body");

        verify(scheduledJobService).create(captor.capture());
        assertThat(captor.getValue().scheduleType()).isEqualTo("RECURRING");
        assertThat(captor.getValue().category()).isEqualTo("MONITOR");
        assertThat(captor.getValue().title()).isEqualTo("Daily brief");
        assertThat(captor.getValue().scheduleExpression()).isEqualTo("0 9 * * *");
        assertThat(captor.getValue().notificationTitle()).isEqualTo("Brief");
        assertThat(captor.getValue().notificationBody()).isEqualTo("body");
        assertThat(json).contains("\"ok\":true").contains("j9");
    }

    @Test
    void createScheduledJob_rejectsUnknownScheduleType() {
        String json = tools.createScheduledJob("EVERY_DAY", "REMINDER", "Daily brief",
                "0 9 * * *", "Brief", null);

        assertThat(json).contains("\"errorType\":\"VALIDATION\"").contains("ONE_SHOT, RECURRING");
    }

    @Test
    void createScheduledJob_rejectsUnknownCategory() {
        String json = tools.createScheduledJob("RECURRING", "URGENT", "Daily brief",
                "0 9 * * *", "Brief", null);

        assertThat(json).contains("\"errorType\":\"VALIDATION\"").contains("REMINDER, MONITOR, BRIEF");
    }

    @Test
    void createScheduledJob_rejectsMissingRequiredField() {
        String json = tools.createScheduledJob("RECURRING", "REMINDER", "  ",
                "0 9 * * *", "Brief", null);

        assertThat(json).contains("\"errorType\":\"VALIDATION\"").contains("title is required");
    }

    @Test
    void cancelScheduledJob_deletesById() {
        String json = tools.cancelScheduledJob("j1");

        verify(scheduledJobService).delete("j1");
        assertThat(json).contains("\"ok\":true").contains("j1");
    }

    @Test
    void getLatestConversation_returnsSummaryOfNewestConversation() {
        UUID runId = UUID.randomUUID();
        Run run = Run.builder().id(runId).conversationId("conv-1").createdAt(NOW).build();
        when(runRepository.findAll()).thenReturn(List.of(run));
        when(runRepository.findByConversationIdOrderByCreatedAtAsc("conv-1")).thenReturn(List.of(run));

        String json = tools.getLatestConversation();

        assertThat(json).contains("\"ok\":true").contains("conv-1").contains("\"runCount\":1");
    }

    @Test
    void getLatestConversation_returnsNullDataWhenNoConversationExists() {
        when(runRepository.findAll()).thenReturn(List.of());

        String json = tools.getLatestConversation();

        assertThat(json).contains("\"ok\":true").contains("\"data\":null");
    }

    @Test
    void getConversationTimeline_mapsTrajectoriesToEntries() {
        UUID runId = UUID.randomUUID();
        Run run = Run.builder().id(runId).conversationId("conv-1").createdAt(NOW).build();
        SessionTrajectory message = SessionTrajectory.builder()
                .id(UUID.randomUUID()).runId(runId).turnNumber(0)
                .role("user").content("hello aria").createdAt(NOW).build();
        when(runRepository.findByConversationIdOrderByCreatedAtAsc("conv-1")).thenReturn(List.of(run));
        when(trajectoryRepository.findByRunIdInOrderByTurnNumberAsc(List.of(runId)))
                .thenReturn(List.of(message));

        String json = tools.getConversationTimeline("conv-1");

        assertThat(json).contains("\"ok\":true").contains("hello aria")
                .contains("\"role\":\"user\"").contains(runId.toString());
    }

    @Test
    void getConversationTimeline_returnsEmptyListForUnknownConversation() {
        when(runRepository.findByConversationIdOrderByCreatedAtAsc("unknown")).thenReturn(List.of());

        String json = tools.getConversationTimeline("unknown");

        assertThat(json).contains("\"ok\":true").contains("\"data\":[]");
    }
}
