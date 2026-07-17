-- V20: Scheduled jobs table (Issue #58).
-- Stores user-manageable scheduled tasks (reminders, monitors, briefs).
-- user_id is nullable for single-user MVP; reserved for multi-user migration.

CREATE TABLE scheduled_jobs (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NULL,
    schedule_type VARCHAR(20) NOT NULL,
    category VARCHAR(20) NOT NULL,
    title VARCHAR(255) NOT NULL,
    schedule_expression VARCHAR(100) NOT NULL,
    next_fire_at TIMESTAMP NULL,
    last_fired_at TIMESTAMP NULL,
    status VARCHAR(20) NOT NULL,
    notification_title VARCHAR(255) NOT NULL,
    notification_body TEXT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NULL
);

CREATE INDEX idx_jobs_status ON scheduled_jobs(status);
CREATE INDEX idx_jobs_category ON scheduled_jobs(category);
CREATE INDEX idx_jobs_next_fire ON scheduled_jobs(next_fire_at);
