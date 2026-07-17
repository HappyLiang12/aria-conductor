package io.aria.conductor.aria.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "aria_scheduled_jobs")
public class ScheduledJobEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "user_id", length = 36)
    private String userId;

    @Column(name = "schedule_type", nullable = false, length = 20)
    private String scheduleType;

    @Column(nullable = false, length = 20)
    private String category;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(name = "schedule_expression", nullable = false, length = 100)
    private String scheduleExpression;

    @Column(name = "next_fire_at")
    private Instant nextFireAt;

    @Column(name = "last_fired_at")
    private Instant lastFiredAt;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "notification_title", nullable = false, length = 255)
    private String notificationTitle;

    @Column(name = "notification_body", columnDefinition = "TEXT")
    private String notificationBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public ScheduledJobEntity() {}

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
}
