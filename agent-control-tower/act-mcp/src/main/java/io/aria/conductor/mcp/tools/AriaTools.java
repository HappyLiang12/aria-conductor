package io.aria.conductor.mcp.tools;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.aria.dto.ConversationSummary;
import io.aria.conductor.aria.dto.NotificationDto;
import io.aria.conductor.aria.dto.ScheduledJobDto;
import io.aria.conductor.aria.dto.TimelineEntry;
import io.aria.conductor.aria.service.NotificationService;
import io.aria.conductor.aria.service.ScheduledJobService;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.ScheduleType;
import io.aria.conductor.common.model.SessionTrajectory;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Aria assistant tools: notifications, scheduled jobs and conversation history.
 * Thin wrappers over the same services/repositories the REST controllers call —
 * REST/dashboard and MCP stay at parity.
 *
 * aria_chat is deliberately NOT exposed: AriaService.chat starts a new Aria run,
 * so a sandboxed agent could recursively invoke Aria from inside her own run.
 */
@Component
@RequiredArgsConstructor
public class AriaTools implements McpTool {

    private static final List<String> JOB_CATEGORIES = List.of("REMINDER", "MONITOR", "BRIEF");

    private final NotificationService notificationService;
    private final ScheduledJobService scheduledJobService;
    private final RunRepository runRepository;
    private final SessionTrajectoryRepository trajectoryRepository;
    private final McpProperties mcpProperties;

    @Tool(name = "list_notifications",
            description = "List notifications, newest first. Optional page (default 0) and size (default 20).")
    public String listNotifications(
            @ToolParam(description = "Zero-based page index, default 0", required = false) Integer page,
            @ToolParam(description = "Page size, default 20", required = false) Integer size) {
        try {
            Page<NotificationDto> result = notificationService.list(
                    page == null ? 0 : page, size == null ? 20 : size);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("content", result.getContent());
            payload.put("page", result.getNumber());
            payload.put("size", result.getSize());
            payload.put("totalElements", result.getTotalElements());
            payload.put("totalPages", result.getTotalPages());
            return ToolResponses.ok(payload);
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("NOTIFICATION_LIST_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "get_unread_notification_count",
            description = "Return the number of unread notifications ({unreadCount}).")
    public String getUnreadNotificationCount() {
        try {
            return ToolResponses.ok(notificationService.getUnreadCount());
        } catch (Exception e) {
            return ToolResponses.error("NOTIFICATION_COUNT_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "mark_notification_read",
            description = "Mark one notification as read by id.")
    public String markNotificationRead(@ToolParam(description = "Notification id") String id) {
        try {
            return ToolResponses.ok(notificationService.markRead(id));
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("NOTIFICATION_READ_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "mark_all_notifications_read",
            description = "Mark every notification as read.")
    public String markAllNotificationsRead() {
        try {
            notificationService.markAllRead();
            return ToolResponses.ok(Map.of("markedAllRead", true));
        } catch (Exception e) {
            return ToolResponses.error("NOTIFICATION_UPDATE_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "list_scheduled_jobs",
            description = "List scheduled jobs. Optional category (REMINDER/MONITOR/BRIEF) and status (ACTIVE/PAUSED/COMPLETED); blank lists all.")
    public String listScheduledJobs(
            @ToolParam(description = "Category or blank for all", required = false) String category,
            @ToolParam(description = "Status or blank for all", required = false) String status) {
        try {
            String c = category == null || category.isBlank() ? null : category.trim();
            String s = status == null || status.isBlank() ? null : status.trim();
            return ToolResponses.ok(scheduledJobService.list(c, s));
        } catch (Exception e) {
            return ToolResponses.error("JOB_LIST_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "create_scheduled_job",
            description = "Create an ACTIVE scheduled job that fires a notification. scheduleType ONE_SHOT uses the next cron match as a one-off delay; RECURRING repeats on the cron expression (5 fields, min hour day month weekday). category is REMINDER, MONITOR or BRIEF. Use cancel_scheduled_job to stop it.")
    public String createScheduledJob(
            @ToolParam(description = "ONE_SHOT or RECURRING") String scheduleType,
            @ToolParam(description = "REMINDER, MONITOR or BRIEF") String category,
            @ToolParam(description = "Human-readable job title") String title,
            @ToolParam(description = "Cron expression, e.g. '0 9 * * *'") String scheduleExpression,
            @ToolParam(description = "Notification title sent when the job fires") String notificationTitle,
            @ToolParam(description = "Notification body sent when the job fires", required = false) String notificationBody) {
        try {
            requireText(scheduleType, "scheduleType");
            requireText(category, "category");
            requireText(title, "title");
            requireText(scheduleExpression, "scheduleExpression");
            requireText(notificationTitle, "notificationTitle");
            ScheduledJobDto input = new ScheduledJobDto(
                    null,
                    parseScheduleType(scheduleType).name(),
                    parseCategory(category),
                    title,
                    scheduleExpression,
                    notificationTitle,
                    null,
                    null,
                    notificationBody,
                    null,
                    null,
                    null);
            return ToolResponses.ok(scheduledJobService.create(input));
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("JOB_CREATE_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "cancel_scheduled_job",
            description = "Cancel (delete) a scheduled job by id so it never fires again.")
    public String cancelScheduledJob(@ToolParam(description = "Scheduled job id") String id) {
        try {
            scheduledJobService.delete(id);
            return ToolResponses.ok(Map.of("cancelledJobId", id));
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("JOB_CANCEL_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "get_latest_conversation",
            description = "Return the most recent Aria conversation (conversationId, lastMessageAt, runCount). data is null when no conversation exists yet.")
    public String getLatestConversation() {
        try {
            Run latest = runRepository.findAll().stream()
                    .filter(r -> r.getConversationId() != null && !r.getConversationId().isBlank())
                    .max(Comparator.comparing(Run::getCreatedAt))
                    .orElse(null);
            if (latest == null) {
                return ToolResponses.ok(null);
            }
            String conversationId = latest.getConversationId();
            ConversationSummary summary = ConversationSummary.builder()
                    .conversationId(conversationId)
                    .lastMessageAt(latest.getCreatedAt())
                    .runCount(runRepository.findByConversationIdOrderByCreatedAtAsc(conversationId).size())
                    .build();
            return ToolResponses.ok(summary);
        } catch (Exception e) {
            return ToolResponses.error("CONVERSATION_READ_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "get_conversation_timeline",
            description = "Return the full message timeline of a conversation across all its runs: role, content, timestamp and runId per entry, ordered by run creation time then turn. Returns an empty list for an unknown conversationId.")
    public String getConversationTimeline(
            @ToolParam(description = "Conversation id, e.g. from get_latest_conversation") String conversationId) {
        try {
            List<Run> runs = runRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
            if (runs.isEmpty()) {
                return ToolResponses.ok(List.of());
            }
            List<UUID> runIds = runs.stream().map(Run::getId).toList();
            List<SessionTrajectory> trajectories = trajectoryRepository
                    .findByRunIdInOrderByTurnNumberAsc(runIds);
            Map<UUID, Instant> runCreated = runs.stream()
                    .collect(Collectors.toMap(Run::getId, Run::getCreatedAt));
            List<TimelineEntry> timeline = trajectories.stream()
                    .sorted(Comparator.comparing(
                            t -> runCreated.getOrDefault(t.getRunId(), t.getCreatedAt())))
                    .map(t -> TimelineEntry.builder()
                            .role(t.getRole())
                            .content(t.getContent())
                            .timestamp(t.getCreatedAt())
                            .runId(t.getRunId().toString())
                            .build())
                    .toList();
            return ToolResponses.ok(timeline);
        } catch (Exception e) {
            return ToolResponses.error("CONVERSATION_READ_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    private static ScheduleType parseScheduleType(String raw) {
        try {
            return ScheduleType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid scheduleType '" + raw
                    + "'. Valid: ONE_SHOT, RECURRING");
        }
    }

    private static String parseCategory(String raw) {
        String category = raw.trim().toUpperCase();
        if (!JOB_CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("Invalid category '" + raw
                    + "'. Valid: REMINDER, MONITOR, BRIEF");
        }
        return category;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
