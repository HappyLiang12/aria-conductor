package io.aria.conductor.common.model;

import java.time.Instant;
import java.util.function.Consumer;

/**
 * Domain model representing a scheduled job for Aria notifications.
 * Used as a DTO between modules via SchedulerPort.
 */
public class ScheduledJob {

    private String id;
    private String userId;
    private String scheduleType;
    private String category;
    private String title;
    private String scheduleExpression;
    private Instant nextFireAt;
    private Instant lastFiredAt;
    private String status;
    private String notificationTitle;
    private String notificationBody;
    private Instant createdAt;
    private Instant updatedAt;
    private transient Consumer<String> onFireCallback;

    public ScheduledJob() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getScheduleType() { return scheduleType; }
    public void setScheduleType(String scheduleType) { this.scheduleType = scheduleType; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getScheduleExpression() { return scheduleExpression; }
    public void setScheduleExpression(String scheduleExpression) { this.scheduleExpression = scheduleExpression; }
    public Instant getNextFireAt() { return nextFireAt; }
    public void setNextFireAt(Instant nextFireAt) { this.nextFireAt = nextFireAt; }
    public Instant getLastFiredAt() { return lastFiredAt; }
    public void setLastFiredAt(Instant lastFiredAt) { this.lastFiredAt = lastFiredAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getNotificationTitle() { return notificationTitle; }
    public void setNotificationTitle(String notificationTitle) { this.notificationTitle = notificationTitle; }
    public String getNotificationBody() { return notificationBody; }
    public void setNotificationBody(String notificationBody) { this.notificationBody = notificationBody; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Consumer<String> getOnFireCallback() { return onFireCallback; }
    public void setOnFireCallback(Consumer<String> onFireCallback) { this.onFireCallback = onFireCallback; }
}
