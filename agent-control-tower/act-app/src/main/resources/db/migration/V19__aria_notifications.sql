-- V19: Aria proactive notifications table (Issue #58).
-- Stores system event notifications for the notification bell UI.
-- user_id is nullable for single-user MVP; reserved for multi-user migration.

CREATE TABLE aria_notifications (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NULL,
    type VARCHAR(50) NOT NULL,
    title VARCHAR(255) NOT NULL,
    body TEXT NULL,
    resource_type VARCHAR(50) NULL,
    resource_id VARCHAR(36) NULL,
    job_id VARCHAR(36) NULL,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_notif_created ON aria_notifications(created_at);
CREATE INDEX idx_notif_unread ON aria_notifications(is_read, created_at);
